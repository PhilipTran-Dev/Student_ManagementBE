package Student_Management.class_service.service;

import Student_Management.class_service.dto.*;
import Student_Management.class_service.entity.Class;
import Student_Management.class_service.entity.ClassMember;
import Student_Management.class_service.entity.ClassMemberStatus;
import Student_Management.class_service.repository.ClassMemberRepository;
import Student_Management.class_service.repository.ClassRepository;
import Student_Management.class_service.utils.ClassCodeGenerator;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClassService {

    private final ClassRepository classRepository;
    private final ClassMemberRepository classMemberRepository;
    private final ClassCodeGenerator classCodeGenerator;
    private final WebClient userServiceWebClient;

    @Transactional
    public ClassResponse createClass(ClassRequest request) {
        UserPrincipal currentUser = (UserPrincipal) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();

        Long teacherId = currentUser.getId();

        String classCode;
        do {
            classCode = classCodeGenerator.generateRandomCode();
        } while (classRepository.existsByCode(classCode));

        Class classroom = Class.builder()
                .name(request.getName())
                .courseId(request.getCourseId())
                .semesterId(request.getSemesterId())
                .teacherId(teacherId)
                .code(classCode)
                .build();

        Class savedClass = classRepository.save(classroom);

        ClassMember teacherMember = ClassMember.builder()
                .classroom(savedClass)
                .userId(teacherId)
                .role("TEACHER")
                .status(ClassMemberStatus.ACTIVE)
                .build();

        classMemberRepository.save(teacherMember);

        return convertToResponse(savedClass);
    }

    private ClassResponse convertToResponse(Class classroom) {
        return ClassResponse.builder()
                .id(classroom.getId())
                .name(classroom.getName())
                .code(classroom.getCode())
                .courseId(classroom.getCourseId())
                .semesterId(classroom.getSemesterId())
                .password(classroom.getPassword())
                .teacherId(classroom.getTeacherId())
                .createdAt(classroom.getCreatedAt())
                .updatedAt(classroom.getUpdatedAt())
                .build();
    }

    @Transactional(readOnly = true)
    @CircuitBreaker(name = "userService", fallbackMethod = "getTeacherClassesFallback")
    @Retry(name = "userService")
    public List<ClassResponse> getTeacherClasses() {
        UserPrincipal currentUser = (UserPrincipal) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();

        Long teacherId = currentUser.getId();

        UserDto teacherDto = userServiceWebClient.get()
                .uri("/api/v1/teacher/" + teacherId)
                .retrieve()
                .bodyToMono(UserDto.class)
                .block();

        String finalTeacherName = teacherDto != null ? teacherDto.getFullName() : "Unknown Teacher";
        String finalTeacherEmail = teacherDto != null ? teacherDto.getEmail() : "N/A";

        return classRepository.findByTeacherId(teacherId)
                .stream()
                .map(classroom -> {
                    ClassResponse response = convertToResponse(classroom);
                    response.setTeacherName(finalTeacherName);
                    response.setTeacherEmail(finalTeacherEmail);
                    return response;
                })
                .toList();
    }

    public List<ClassResponse> getTeacherClassesFallback(Throwable throwable) {
        log.error("Lỗi khi kết nối user-service tại getTeacherClasses. Nguyên nhân: {}", throwable.getMessage());
        UserPrincipal currentUser = (UserPrincipal) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();

        return classRepository.findByTeacherId(currentUser.getId())
                .stream()
                .map(classroom -> {
                    ClassResponse response = convertToResponse(classroom);
                    response.setTeacherName("Unknown Teacher");
                    response.setTeacherEmail("N/A");
                    return response;
                })
                .toList();
    }

    @Transactional
    public ClassResponse updateClassPassword(Long classId, String password) {
        UserPrincipal currentUser = (UserPrincipal) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();

        Class classroom = classRepository.findById(classId)
                .orElseThrow(() -> new IllegalArgumentException("Cannot find class with id: " + classId));

        if (!classroom.getTeacherId().equals(currentUser.getId())) {
            throw new IllegalStateException("Only the teacher of this class can update the password!");
        }

        classroom.setPassword(password);
        Class updated = classRepository.save(classroom);
        return convertToResponse(updated);
    }

    @Transactional
    public void joinClass(JoinClassRequest request) {
        UserPrincipal currentUser = (UserPrincipal) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();

        String inputCode = request.getCode() != null ? request.getCode().trim() : "";
        Class classroom = classRepository.findByCode(inputCode)
                .orElseThrow(() -> new IllegalArgumentException("Class code is not exist!"));

        String classPassword = classroom.getPassword();
        if (classPassword != null && !classPassword.isBlank()) {
            if (request.getPassword() == null || !request.getPassword().equals(classPassword)) {
                throw new IllegalArgumentException("This password is not correct!");
            }
        }

        java.util.Optional<ClassMember> existingMember = classMemberRepository
                .findByClassroomAndUserId(classroom, currentUser.getId());

        if (existingMember.isPresent()) {
            ClassMember member = existingMember.get();
            if (member.getStatus() == ClassMemberStatus.ACTIVE) {
                throw new IllegalStateException("You have joined this class before!");
            } else {
                member.setStatus(ClassMemberStatus.ACTIVE);
                classMemberRepository.save(member);
            }
        } else {
            ClassMember newMember = ClassMember.builder()
                    .classroom(classroom)
                    .userId(currentUser.getId())
                    .role("STUDENT")
                    .status(ClassMemberStatus.ACTIVE)
                    .build();
            classMemberRepository.save(newMember);
        }
    }

    @Transactional(readOnly = true)
    @CircuitBreaker(name = "userService", fallbackMethod = "getStudentClassesFallback")
    @Retry(name = "userService")
    public List<ClassResponse> getStudentClasses() {
        UserPrincipal currentUser = (UserPrincipal) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();

        List<ClassMember> memberships = classMemberRepository.findByUserIdAndRoleAndStatus(
                currentUser.getId(), "STUDENT", ClassMemberStatus.ACTIVE);

        return memberships.stream()
                .map(membership -> {
                    Class classroom = membership.getClassroom();
                    ClassResponse response = convertToResponse(classroom);

                    UserDto teacherDto = userServiceWebClient.get()
                            .uri("/api/v1/teacher/" + classroom.getTeacherId())
                            .retrieve()
                            .bodyToMono(UserDto.class)
                            .block();

                    if (teacherDto != null) {
                        response.setTeacherName(teacherDto.getFullName());
                        response.setTeacherEmail(teacherDto.getEmail());
                    }
                    return response;
                })
                .toList();
    }

    public List<ClassResponse> getStudentClassesFallback(Throwable throwable) {
        log.error("Error connect to user-service in getStudentClasses. Reason: {}", throwable.getMessage());
        UserPrincipal currentUser = (UserPrincipal) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();

        List<ClassMember> memberships = classMemberRepository.findByUserIdAndRoleAndStatus(
                currentUser.getId(), "STUDENT", ClassMemberStatus.ACTIVE);

        return memberships.stream()
                .map(membership -> {
                    ClassResponse response = convertToResponse(membership.getClassroom());
                    response.setTeacherName("Annonymous Teacher");
                    response.setTeacherEmail("N/A");
                    return response;
                })
                .toList();
    }

    @Transactional(readOnly = true)
    @CircuitBreaker(name = "userService", fallbackMethod = "getClassMembersFallback")
    @Retry(name = "userService")
    public List<ClassMemberResponse> getClassMembers(Long classId) {
        Class classroom = classRepository.findById(classId)
                .orElseThrow(() -> new IllegalArgumentException("cannot find class with id: " + classId));

        List<ClassMember> activeMembers = classMemberRepository
                .findByClassroomAndStatus(classroom, ClassMemberStatus.ACTIVE);

        return activeMembers.stream()
                .filter(m -> "STUDENT".equalsIgnoreCase(m.getRole()))
                .map(member -> {
                    ClassMemberResponse.ClassMemberResponseBuilder builder = ClassMemberResponse.builder()
                            .userId(member.getUserId())
                            .joinedAt(member.getJoinedAt());

                    StudentDetailDto studentDto = userServiceWebClient.get()
                            .uri("/api/v1/student/" + member.getUserId())
                            .retrieve()
                            .bodyToMono(StudentDetailDto.class)
                            .block();

                    if (studentDto != null) {
                        builder.fullName(studentDto.getFullName())
                                .email(studentDto.getEmail())
                                .studentId(studentDto.getStudentId())
                                .className(studentDto.getClassName());
                    }
                    return builder.build();
                })
                .toList();
    }

    public List<ClassMemberResponse> getClassMembersFallback(Long classId, Throwable throwable) {
        log.error("Error connect to user-service in getClassMembers (classId: {}). Reason: {}", classId, throwable.getMessage());
        Class classroom = classRepository.findById(classId)
                .orElseThrow(() -> new IllegalArgumentException("cannot find class with id: " + classId));

        List<ClassMember> activeMembers = classMemberRepository
                .findByClassroomAndStatus(classroom, ClassMemberStatus.ACTIVE);

        return activeMembers.stream()
                .filter(m -> "STUDENT".equalsIgnoreCase(m.getRole()))
                .map(member -> ClassMemberResponse.builder()
                        .userId(member.getUserId())
                        .joinedAt(member.getJoinedAt())
                        .fullName("student name not found")
                        .email("N/A")
                        .build())
                .toList();
    }

    @Transactional(readOnly = true)
    @CircuitBreaker(name = "userService", fallbackMethod = "getClassByIdFallback")
    @Retry(name = "userService")
    public ClassResponse getClassById(Long classId) {
        Class classroom = classRepository.findById(classId)
                .orElseThrow(() -> new IllegalArgumentException("Cannot find class with id: " + classId));

        ClassResponse response = convertToResponse(classroom);

        UserDto teacherDto = userServiceWebClient.get()
                .uri("/api/v1/teacher/" + classroom.getTeacherId())
                .retrieve()
                .bodyToMono(UserDto.class)
                .block();

        if (teacherDto != null) {
            response.setTeacherName(teacherDto.getFullName());
            response.setTeacherEmail(teacherDto.getEmail());
        }
        return response;
    }

    public ClassResponse getClassByIdFallback(Long classId, Throwable throwable) {
        log.error("Error connect user-service in getClassById (classId: {}). Reason: {}", classId, throwable.getMessage());
        Class classroom = classRepository.findById(classId)
                .orElseThrow(() -> new IllegalArgumentException("Cannot find class with id: " + classId));

        ClassResponse response = convertToResponse(classroom);
        response.setTeacherName("Unknown Instructor");
        response.setTeacherEmail("N/A");
        return response;
    }
}