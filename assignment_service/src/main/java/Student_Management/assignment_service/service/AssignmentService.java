package Student_Management.assignment_service.service;

import Student_Management.assignment_service.dto.*;
import Student_Management.assignment_service.entity.*;
import Student_Management.assignment_service.repository.AssignmentRepository;
import Student_Management.assignment_service.repository.SubmissionRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.minio.*;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AssignmentService {

    private final AssignmentRepository assignmentRepository;
    private final SubmissionRepository submissionRepository;
    private final MinioClient minioClient;
    private final WebClient classServiceWebClient;

    @Value("${app.minio.bucket}")
    private String bucket;

    // ----- TEACHER LOGIC -----

    @Transactional
    public Assignment createAssignment(AssignmentRequest request, List<MultipartFile> files) {
        Long teacherId = getUserIdFromContext();
        verifyTeacherAccess(request.getClassId(), teacherId);

        List<String> fileUrls = new ArrayList<>();
        if (files != null && !files.isEmpty()) {
            fileUrls = uploadFilesToMinio(files);
        }

        Assignment assignment = Assignment.builder()
                .classId(request.getClassId())
                .title(request.getTitle())
                .description(request.getDescription())
                .deadline(request.getDeadline())
                .maxMark(request.getMaxMark())
                .createdBy(teacherId)
                .attachments(fileUrls)
                .build();

        return assignmentRepository.save(assignment);
    }

    @Transactional
    public Assignment updateAssignment(Long id, AssignmentUpdateRequest request, List<MultipartFile> files) {
        Assignment assignment = assignmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Assignment not found with id: " + id));

        assignment.setTitle(request.getTitle());
        assignment.setDescription(request.getDescription());
        assignment.setDeadline(request.getDeadline());
        assignment.setMaxMark(request.getMaxMark());

        if (files != null && !files.isEmpty()) {
            List<String> currentFiles = assignment.getAttachments();
            if (currentFiles == null) {
                currentFiles = new ArrayList<>();
            }

            List<String> newUploadedFiles = uploadFilesToMinio(files);
            currentFiles.addAll(newUploadedFiles);

            assignment.setAttachments(currentFiles);
        }

        return assignmentRepository.save(assignment);
    }

    @Transactional
    public Submission gradeSubmission(Long submissionId, GradeRequest request) {
        Long teacherId = getUserIdFromContext();
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new IllegalArgumentException("Submission not found"));

        verifyTeacherAccess(submission.getAssignment().getClassId(), teacherId);

        if (request.getGrade() > submission.getAssignment().getMaxMark() || request.getGrade() < 0) {
            throw new IllegalArgumentException("Invalid grade value");
        }

        submission.setGrade(request.getGrade());
        submission.setFeedback(request.getFeedback());

        return submissionRepository.save(submission);
    }

    // ----- STUDENT LOGIC -----

    @Transactional(readOnly = true)
    public List<StudentAssignmentResponse> getStudentAssignments(Long classId) {
        Long studentId = getUserIdFromContext();
        verifyStudentAccess(classId, studentId);

        List<Assignment> assignments = assignmentRepository.findByClassIdOrderByDeadlineDesc(classId);

        return assignments.stream().map(assignment -> {
            Submission submission = submissionRepository.findByAssignmentAndStudentId(assignment, studentId).orElse(null);
            return buildStudentResponse(assignment, submission);
        }).collect(Collectors.toList());
    }

    @Transactional
    public Submission submitAssignment(Long assignmentId, List<MultipartFile> files) {
        Long studentId = getUserIdFromContext();
        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new IllegalArgumentException("Assignment not found"));

        verifyStudentAccess(assignment.getClassId(), studentId);

        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("Must upload at least one file");
        }

        Submission existing = submissionRepository.findByAssignmentAndStudentId(assignment, studentId).orElse(null);
        if (existing != null) {
            throw new IllegalStateException("Already submitted. Please unsubmit first.");
        }

        List<String> fileUrls = uploadFilesToMinio(files);
        LocalDateTime now = LocalDateTime.now();
        SubmissionStatus status = now.isAfter(assignment.getDeadline()) ? SubmissionStatus.LATE : SubmissionStatus.ON_TIME;

        Submission submission = Submission.builder()
                .assignment(assignment)
                .studentId(studentId)
                .fileUrls(fileUrls)
                .status(status)
                .build();

        return submissionRepository.save(submission);
    }

    // ----- CORE PRIVATE METHODS -----

    private StudentAssignmentResponse buildStudentResponse(Assignment assignment, Submission submission) {
        AssignmentState state;
        if (submission != null) {
            state = AssignmentState.DONE;
        } else {
            state = LocalDateTime.now().isAfter(assignment.getDeadline().plusDays(2))
                    ? AssignmentState.MISSING : AssignmentState.TODO;
        }

        return StudentAssignmentResponse.builder()
                .id(assignment.getId())
                .classId(assignment.getClassId())
                .title(assignment.getTitle())
                .description(assignment.getDescription())
                .deadline(assignment.getDeadline())
                .maxMark(assignment.getMaxMark())
                .attachments(assignment.getAttachments())
                .state(state)
                .earnedGrade(submission != null ? submission.getGrade() : null)
                .feedback(submission != null ? submission.getFeedback() : null)
                .submittedAt(submission != null ? submission.getSubmittedAt() : null)
                .submissionStatus(submission != null ? submission.getStatus().name() : null)
                .submittedFiles(submission != null ? submission.getFileUrls() : java.util.Collections.emptyList())
                .build();
    }

    private List<String> uploadFilesToMinio(List<MultipartFile> files) {
        List<String> fileNames = new ArrayList<>();
        try {
            boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!found) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }

            for (MultipartFile file : files) {
                String fileId = UUID.randomUUID() + "_" + file.getOriginalFilename();
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(bucket).object(fileId)
                        .stream(file.getInputStream(), file.getSize(), -1)
                        .contentType(file.getContentType()).build());
                fileNames.add(fileId);
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("MinIO Upload Failed: " + e.getMessage(), e);
        }
        return fileNames;
    }

    private Long getUserIdFromContext() {
        UserPrincipal user = (UserPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return user.getId();
    }

    @Transactional(readOnly = true)
    public String generateDownloadUrl(String objectName) {
        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucket)
                            .object(objectName)
                            .expiry(15, TimeUnit.MINUTES)
                            .build()
            );
        } catch (Exception e) {
            throw new RuntimeException("Error when generating preview/download link from MinIO", e);
        }
    }

    @Transactional
    public void deleteAssignment(Long id) {
        Assignment assignment = assignmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Assignment not found"));

        if (assignment.getAttachments() != null) {
            for (String fileId : assignment.getAttachments()) {
                try {
                    minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(fileId).build());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        assignmentRepository.delete(assignment);
    }

    @Transactional
    public void deleteFileFromAssignment(Long assignmentId, String fileName) {
        Assignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new IllegalArgumentException("Assignment not found"));

        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(fileName).build());
        } catch (Exception e) {
            throw new RuntimeException("Error deleting file from MinIO", e);
        }

        // delete the file from the list of attachments
        List<String> files = assignment.getAttachments();
        if (files != null) {
            files.remove(fileName);
            assignment.setAttachments(files);
            assignmentRepository.save(assignment);
        }
    }


    @Transactional(readOnly = true)
    @CircuitBreaker(name = "classService", fallbackMethod = "getClassGradebookFallback")
    @Retry(name = "classService")
    public GradebookResponse getClassGradebook(Long classId) {
        List<ClassMemberResponse> classMembers = classServiceWebClient.get()
                .uri("/api/v1/classes/{classId}/members", classId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<ClassMemberResponse>>() {})
                .timeout(Duration.ofSeconds(3))
                .block();

        if (classMembers == null) classMembers = Collections.emptyList();

        List<Assignment> assignments = assignmentRepository.findByClassIdOrderByDeadlineDesc(classId);

        List<GradebookAssignmentInfo> assignmentInfos = assignments.stream()
                .map(a -> GradebookAssignmentInfo.builder()
                        .id(a.getId())
                        .title(a.getTitle())
                        .maxMark(a.getMaxMark())
                        .build())
                .collect(Collectors.toList());

        List<GradebookStudentRow> studentRows = new ArrayList<>();

        for (ClassMemberResponse member : classMembers) {
            Map<Long, Double> studentGradeMap = new java.util.HashMap<>();

            for (Assignment assignment : assignments) {
                Submission submission = submissionRepository
                        .findByAssignmentAndStudentId(assignment, member.getUserId())
                        .orElse(null);

                studentGradeMap.put(assignment.getId(), submission != null ? submission.getGrade() : null);
            }

            studentRows.add(GradebookStudentRow.builder()
                    .studentId(member.getUserId())
                    .studentName(member.getFullName())
                    .studentCode(member.getStudentId())
                    .grades(studentGradeMap)
                    .build());
        }

        return GradebookResponse.builder()
                .assignments(assignmentInfos)
                .studentRows(studentRows)
                .build();
    }
 // fallback function
    public GradebookResponse getClassGradebookFallback(Long classId, Throwable throwable) {
        log.error("Cannot find class {}. Reason: {}",
                classId, throwable.getMessage());

        List<Assignment> assignments = assignmentRepository.findByClassIdOrderByDeadlineDesc(classId);
        List<GradebookAssignmentInfo> assignmentInfos = assignments.stream()
                .map(a -> GradebookAssignmentInfo.builder()
                        .id(a.getId())
                        .title(a.getTitle())
                        .maxMark(a.getMaxMark())
                        .build())
                .collect(Collectors.toList());

        return GradebookResponse.builder()
                .assignments(assignmentInfos)
                .studentRows(Collections.emptyList())
                .build();
    }


    private void verifyTeacherAccess(Long classId, Long teacherId) {
        // Giả lập hoặc gọi sang class-service qua WebClient
    }

    private void verifyStudentAccess(Long classId, Long studentId) {
        // Giả lập hoặc gọi sang class-service qua WebClient
    }

    // ----- STUDENT LOGIC -----:
    @Transactional(readOnly = true)
    public StudentStatsResponse getStudentStats() {
        Long studentId = getUserIdFromContext();

        List<Submission> submissions = submissionRepository.findByStudentId(studentId);

        long gradedCount = submissions.stream().filter(s -> s.getGrade() != null).count();
        long pendingCount = submissions.stream().filter(s -> s.getGrade() == null).count();

        double totalPercentage = 0.0;
        for (Submission s : submissions) {
            if (s.getGrade() != null) {
                double max = s.getAssignment().getMaxMark();
                if (max > 0) {
                    totalPercentage += (s.getGrade() / max) * 100;
                }
            }
        }

        double avgPercentage = gradedCount > 0 ? totalPercentage / gradedCount : 0.0;
        double gpa4 = (avgPercentage / 100) * 4.0;

        gpa4 = Math.round(gpa4 * 100.0) / 100.0;
        avgPercentage = Math.round(avgPercentage * 100.0) / 100.0;

        String standing = "Good";
        if (gpa4 < 2.0 && gradedCount > 0) {
            standing = "Probation";
        } else if (gpa4 >= 3.6) {
            standing = "Excellent";
        }

        return StudentStatsResponse.builder()
                .currentGpa(gpa4)
                .gpaPercentage(avgPercentage)
                .gradedTasks(gradedCount)
                .pendingTasks(pendingCount)
                .academicStanding(standing)
                .build();
    }
}