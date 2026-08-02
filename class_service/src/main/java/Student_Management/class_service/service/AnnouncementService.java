package Student_Management.class_service.service;

import Student_Management.class_service.dto.AnnouncementRequest;
import Student_Management.class_service.dto.AnnouncementResponse;
import Student_Management.class_service.dto.UserDto;
import Student_Management.class_service.dto.UserPrincipal;
import Student_Management.class_service.entity.Announcement;
import Student_Management.class_service.entity.Class;
import Student_Management.class_service.repository.AnnouncementRepository;
import Student_Management.class_service.repository.ClassRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnnouncementService {

    private final AnnouncementRepository announcementRepository;
    private final ClassRepository classRepository;
    private final WebClient userServiceWebClient;

    @Transactional
    public AnnouncementResponse createAnnouncement(Long classId, AnnouncementRequest request) {
        UserPrincipal currentUser = (UserPrincipal) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();

        Class classroom = classRepository.findById(classId)
                .orElseThrow(() -> new IllegalArgumentException("cannot find class with id: " + classId));

        if (!classroom.getTeacherId().equals(currentUser.getId())) {
            throw new IllegalStateException("you are not the teacher of this class, cannot create announcement");
        }

        Announcement announcement = Announcement.builder()
                .classroom(classroom)
                .title(request.getTitle())
                .content(request.getContent())
                .authorId(currentUser.getId())
                .build();

        Announcement saved = announcementRepository.save(announcement);
        return convertToResponse(saved, null);
    }

    @Transactional(readOnly = true)
    @CircuitBreaker(name = "userService", fallbackMethod = "getAnnouncementsFallback")
    @Retry(name = "userService")
    public List<AnnouncementResponse> getAnnouncements(Long classId) {
        Class classroom = classRepository.findById(classId)
                .orElseThrow(() -> new IllegalArgumentException("cannot find class with id: " + classId));

        String authorName = "Teacher";

        UserDto teacherDto = userServiceWebClient.get()
                .uri("/api/v1/teacher/" + classroom.getTeacherId())
                .retrieve()
                .bodyToMono(UserDto.class)
                .timeout(Duration.ofSeconds(3))
                .block();

        if (teacherDto != null && teacherDto.getFullName() != null) {
            authorName = teacherDto.getFullName();
        }

        String finalAuthorName = authorName;

        return announcementRepository.findByClassroomOrderByCreatedAtDesc(classroom)
                .stream()
                .map(announcement -> convertToResponse(announcement, finalAuthorName))
                .toList();
    }

    // ----- FALLBACK METHOD -----
    public List<AnnouncementResponse> getAnnouncementsFallback(Long classId, Throwable throwable) {
        log.error("error connect to user-service in getAnnouncements (classId: {}). Reason: {}", classId, throwable.getMessage());
        Class classroom = classRepository.findById(classId)
                .orElseThrow(() -> new IllegalArgumentException("cannot find class with id: " + classId));

        String defaultAuthorName = "Teacher";
        return announcementRepository.findByClassroomOrderByCreatedAtDesc(classroom)
                .stream()
                .map(announcement -> convertToResponse(announcement, defaultAuthorName))
                .toList();
    }   

    @Transactional
    public AnnouncementResponse updateAnnouncement(Long id, AnnouncementRequest request) {
        UserPrincipal currentUser = (UserPrincipal) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();

        Announcement announcement = announcementRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("cannot find announcement with id: " + id));

        if (!announcement.getAuthorId().equals(currentUser.getId())) {
            throw new IllegalStateException("you cannot update this announcement because you are not the author");
        }

        announcement.setTitle(request.getTitle());
        announcement.setContent(request.getContent());
        Announcement updated = announcementRepository.save(announcement);

        return convertToResponse(updated, null);
    }

    @Transactional
    public void deleteAnnouncement(Long id) {
        UserPrincipal currentUser = (UserPrincipal) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();

        Announcement announcement = announcementRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("cannot find announcement with id: " + id));

        if (!announcement.getAuthorId().equals(currentUser.getId())) {
            throw new IllegalStateException("you cannot delete this announcement because you are not the author");
        }

        announcementRepository.delete(announcement);
    }

    private AnnouncementResponse convertToResponse(Announcement announcement, String authorName) {
        return AnnouncementResponse.builder()
                .id(announcement.getId())
                .classId(announcement.getClassroom().getId())
                .title(announcement.getTitle())
                .content(announcement.getContent())
                .authorId(announcement.getAuthorId())
                .authorName(authorName)
                .createdAt(announcement.getCreatedAt())
                .updatedAt(announcement.getUpdatedAt())
                .build();
    }
}