package Student_Management.assignment_service.service;

import Student_Management.assignment_service.dto.*;
import Student_Management.assignment_service.entity.*;
import Student_Management.assignment_service.repository.*;
import Student_Management.event.NotificationEvent;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.minio.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubmissionService {

    private final AssignmentRepository assignmentRepository;
    private final SubmissionRepository submissionRepository;
    private final MinioClient minioClient;
    private final WebClient classServiceWebClient;
    private final WebClient userServiceWebClient;
    private final KafkaTemplate<String, NotificationEvent> kafkaTemplate;

    @Value("${app.minio.bucket}")
    private String bucket;

    @Transactional
    public Submission studentSubmit(Long assignmentId, Long studentId, List<MultipartFile> files) {
        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new IllegalArgumentException("Assignment not found"));

        Optional<Submission> existing = submissionRepository.findByAssignmentAndStudentId(assignment, studentId);
        if (existing.isPresent()) {
            throw new IllegalStateException("You have already submitted this assignment. Unsubmit first.");
        }

        List<String> uploadedFiles = new ArrayList<>();
        try {
            boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!found) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }

            for (MultipartFile file : files) {
                String fileId = "submissions/" + UUID.randomUUID() + "_" + file.getOriginalFilename();
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(bucket).object(fileId)
                        .stream(file.getInputStream(), file.getSize(), -1)
                        .contentType(file.getContentType()).build());
                uploadedFiles.add(fileId);
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("File storage failure: " + e.getMessage(), e);
        }

        SubmissionStatus status = LocalDateTime.now().isAfter(assignment.getDeadline())
                ? SubmissionStatus.LATE : SubmissionStatus.ON_TIME;

        Submission submission = Submission.builder()
                .assignment(assignment)
                .studentId(studentId)
                .fileUrls(uploadedFiles)
                .status(status)
                .build();

        Submission savedSubmission = submissionRepository.save(submission);

        try {
            Long teacherId = assignment.getCreatedBy();
            Map<?, ?> teacherMap = userServiceWebClient.get()
                    .uri("/api/v1/teacher/" + teacherId)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(3))
                    .block();

            if (teacherMap != null && teacherMap.get("email") != null) {
                String teacherEmail = teacherMap.get("email").toString();

                NotificationEvent event = NotificationEvent.newBuilder()
                        .setRecipientEmail(teacherEmail)
                        .setTitle("New Submission: " + assignment.getTitle())
                        .setContent("Student ID " + studentId + " has submitted the assignment. Status: " + savedSubmission.getStatus())
                        .setEventType("SUBMISSION_CREATED")
                        .build();

                kafkaTemplate.send("notification-events-topic", event);
                log.info("Successfully sent SUBMISSION_CREATED event to Kafka for teacher email: {}", teacherEmail);
            }
        } catch (Exception e) {
            log.error("Failed to send Kafka event for submission: {}", e.getMessage());
        }

        return savedSubmission;
    }

    @Transactional
    public void studentUnsubmit(Long assignmentId, Long studentId) {
        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new IllegalArgumentException("Assignment not found"));

        Submission submission = submissionRepository.findByAssignmentAndStudentId(assignment, studentId)
                .orElseThrow(() -> new IllegalArgumentException("No submission found to recall."));

        if (submission.getGrade() != null) {
            throw new IllegalStateException("Cannot unsubmit an assignment that has already been graded.");
        }

        // Remove file on MinIO
        try {
            for (String fileId : submission.getFileUrls()) {
                minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(fileId).build());
            }
        } catch (Exception ignored) {}

        submissionRepository.delete(submission);
    }

    @Transactional(readOnly = true)
    @CircuitBreaker(name = "classService", fallbackMethod = "getTeacherSubmissionDashboardFallback")
    @Retry(name = "classService")
    public List<TeacherSubmissionView> getTeacherSubmissionDashboard(Long assignmentId, String filterStatus) {
        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new IllegalArgumentException("Assignment not found"));

        // Take student list from class service via WebClient
        List<ClassMemberResponse> classMembers = classServiceWebClient.get()
                .uri("/api/v1/classes/{classId}/members", assignment.getClassId())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<ClassMemberResponse>>() {})
                .timeout(Duration.ofSeconds(3))
                .block();

        if (classMembers == null) classMembers = Collections.emptyList();

        // Get all submissions for this assignment
        List<Submission> dynamicSubmissions = submissionRepository.findByAssignmentId(assignmentId);
        Map<Long, Submission> submissionMap = dynamicSubmissions.stream()
                .collect(Collectors.toMap(Submission::getStudentId, s -> s));

        List<TeacherSubmissionView> dashboard = new ArrayList<>();

        for (ClassMemberResponse member : classMembers) {
            Submission sub = submissionMap.get(member.getUserId());
            String calculatedStatus;

            if (sub != null) {
                calculatedStatus = sub.getStatus().name(); // ON_TIME or LATE
            } else {
                // Late over 2 days falls into MISSING status
                calculatedStatus = LocalDateTime.now().isAfter(assignment.getDeadline().plusDays(2))
                        ? "MISSING" : "TO_DO";
            }

            // Filter following status: "TO_DO", "ON_TIME", "LATE", "MISSING"
            if (filterStatus != null && !filterStatus.equalsIgnoreCase("ALL") && !filterStatus.equalsIgnoreCase(calculatedStatus)) {
                continue;
            }

            dashboard.add(TeacherSubmissionView.builder()
                    .studentId(member.getUserId())
                    .studentName(member.getFullName())
                    .studentCode(member.getStudentId())
                    .submissionId(sub != null ? sub.getId() : null)
                    .fileUrls(sub != null ? sub.getFileUrls() : null)
                    .submittedAt(sub != null ? sub.getSubmittedAt() : null)
                    .status(calculatedStatus)
                    .className(member.getClassName())
                    .grade(sub != null ? sub.getGrade() : null)
                    .feedback(sub != null ? sub.getFeedback() : null)
                    .build());
        }

        return dashboard;
    }

    // ----- FALLBACK METHOD -----
    public List<TeacherSubmissionView> getTeacherSubmissionDashboardFallback(Long assignmentId, String filterStatus, Throwable throwable) {
        log.error("Error connect to getTeacherSubmissionDashboard (assignmentId: {}). Details: {}",
                assignmentId, throwable.getMessage());
        return Collections.emptyList();
    }
}