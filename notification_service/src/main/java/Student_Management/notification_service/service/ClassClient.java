package Student_Management.notification_service.service;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

@FeignClient(name = "class-service")
public interface ClassClient {

    @GetMapping("/api/v1/classes/{classId}/students/emails")
    List<String> getStudentEmailsByClassId(@PathVariable("classId") String classId);
}