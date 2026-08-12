package Student_Management.class_service.controller;

import Student_Management.class_service.dto.ClassMemberResponse;
import Student_Management.class_service.dto.ClassRequest;
import Student_Management.class_service.dto.ClassResponse;
import Student_Management.class_service.dto.JoinClassRequest;
import Student_Management.class_service.service.ClassService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/classes")
@RequiredArgsConstructor
public class ClassController {

    private final ClassService classService;

    @GetMapping("/{classId}/students/emails")
    public ResponseEntity<List<String>> getStudentEmailsByClassId(@PathVariable("classId") String classId) {
        List<String> emails = classService.getStudentEmailsByClassId(classId);
        return ResponseEntity.ok(emails);
    }


    // create api for teacher to create class
    @PostMapping("/teacher/create")
    public ResponseEntity<ClassResponse> createClass(@Valid @RequestBody ClassRequest request) {
        ClassResponse response = classService.createClass(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // get all classes of teacher jwt
    @GetMapping("/teacher/all")
    public ResponseEntity<List<ClassResponse>> getTeacherClasses() {
        List<ClassResponse> responses = classService.getTeacherClasses();
        return ResponseEntity.ok(responses);
    }

    // update password of class by teacher
    @PutMapping("/teacher/{classId}/password")
    public ResponseEntity<ClassResponse> updateClassPassword(
            @PathVariable Long classId,
            @RequestBody java.util.Map<String, String> request) {
        String password = request.get("password");
        ClassResponse response = classService.updateClassPassword(classId, password);
        return ResponseEntity.ok(response);
    }

    // student join class by code and password, if password is null, then student can join class without password
    @PostMapping("/student/join")
    public ResponseEntity<Void> joinClass(@jakarta.validation.Valid @RequestBody JoinClassRequest request) {
        classService.joinClass(request);
        return ResponseEntity.ok().build();
    }

    // get all list of classes that student has joined to show on dashboard
    @GetMapping("/student/all")
    public ResponseEntity<List<ClassResponse>> getStudentClasses() {
        return ResponseEntity.ok(classService.getStudentClasses());
    }

    // teacher get list of students that have joined the class (replace sample data)
    @GetMapping("/{classId}/members")
    public ResponseEntity<List<ClassMemberResponse>> getClassMembers(@PathVariable Long classId) {
        return ResponseEntity.ok(classService.getClassMembers(classId));
    }

    @GetMapping("/{classId}")
    public ResponseEntity<ClassResponse> getClassById(@PathVariable Long classId) {
        return ResponseEntity.ok(classService.getClassById(classId));
    }
}