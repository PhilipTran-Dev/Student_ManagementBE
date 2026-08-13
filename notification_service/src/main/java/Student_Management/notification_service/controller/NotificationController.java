package Student_Management.notification_service.controller;

import Student_Management.notification_service.entity.Notification;
import Student_Management.notification_service.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationRepository notificationRepository;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getUserNotifications(@RequestParam("email") String email) {
        List<Notification> list = notificationRepository.findByRecipientEmailOrderByCreatedAtDesc(email);
        long unreadCount = notificationRepository.countByRecipientEmailAndIsReadFalse(email);

        return ResponseEntity.ok(Map.of(
                "notifications", list,
                "unreadCount", unreadCount
        ));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markAsRead(@PathVariable Long id) {
        notificationRepository.findById(id).ifPresent(notification -> {
            notification.setRead(true);
            notificationRepository.save(notification);
        });
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/read-all")
    public ResponseEntity<Void> markAllAsRead(@RequestParam("email") String email) {
        List<Notification> list = notificationRepository.findByRecipientEmailOrderByCreatedAtDesc(email);
        list.forEach(n -> n.setRead(true));
        notificationRepository.saveAll(list);
        return ResponseEntity.ok().build();
    }
}