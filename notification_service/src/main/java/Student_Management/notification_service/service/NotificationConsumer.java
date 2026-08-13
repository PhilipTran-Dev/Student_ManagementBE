package Student_Management.notification_service.service;

import Student_Management.event.NotificationEvent;
import Student_Management.notification_service.entity.Notification;
import Student_Management.notification_service.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationConsumer {

    private final JavaMailSender mailSender;
    private final ClassClient classClient;
    private final NotificationRepository notificationRepository;

    @KafkaListener(topics = "notification-events-topic", groupId = "notification-group")
    public void consume(NotificationEvent event) {
        log.info("📩 [KAFKA RECEIVED] New notification event received. EventType: {}", event.getEventType());

        String title = event.getTitle().toString();
        String content = event.getContent().toString();
        String eventType = event.getEventType().toString();

        if (event.getClassId() != null) {
            String classId = event.getClassId().toString();
            try {
                List<String> emails = classClient.getStudentEmailsByClassId(classId);
                log.info("📬 Found {} students in class {}. Processing notifications...", emails.size(), classId);

                for (String email : emails) {
                    processNotification(email, title, content, eventType);
                }
            } catch (Exception e) {
                log.error("❌ Failed to fetch emails or send class notifications: {}", e.getMessage());
            }
        }
        else if (event.getRecipientEmail() != null) {
            String email = event.getRecipientEmail().toString();
            processNotification(email, title, content, eventType);
        }
    }

    private void processNotification(String email, String title, String content, String eventType) {
        try {
            Notification notification = Notification.builder()
                    .recipientEmail(email)
                    .title(title)
                    .content(content)
                    .eventType(eventType)
                    .isRead(false)
                    .build();
            notificationRepository.save(notification);
            log.info("💾 Notification saved to DB for: {}", email);
        } catch (Exception e) {
            log.error("❌ Failed to save notification to DB for {}: {}", email, e.getMessage());
        }

        sendEmail(email, title, content);
    }

    private void sendEmail(String toEmail, String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(toEmail);
            message.setSubject(subject);
            message.setText(body);

            mailSender.send(message);
            log.info("✅ Email successfully sent to: {}", toEmail);
        } catch (Exception e) {
            log.error("❌ Failed to send email to {}. Error: {}", toEmail, e.getMessage());
        }
    }
}