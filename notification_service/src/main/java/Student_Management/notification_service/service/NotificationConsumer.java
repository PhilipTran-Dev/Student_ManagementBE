package Student_Management.notification_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import Student_Management.event.NotificationEvent;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationConsumer {

    private final JavaMailSender mailSender;
    private final ClassClient classClient;

    @KafkaListener(topics = "notification-events-topic", groupId = "notification-group")
    public void consume(NotificationEvent event) {
        log.info("📩 [KAFKA RECEIVED] New notification event received. EventType: {}", event.getEventType());

        // TH1: Gửi mail cho cả lớp học (nếu có classId)
        if (event.getClassId() != null) {
            String classId = event.getClassId().toString();
            log.info("📢 Fetching student emails for Class ID: {}", classId);

            try {
                List<String> emails = classClient.getStudentEmailsByClassId(classId);
                log.info("📬 Found {} students in class {}. Sending emails...", emails.size(), classId);

                for (String email : emails) {
                    sendEmail(
                            email,
                            event.getTitle().toString(),
                            event.getContent().toString()
                    );
                }
            } catch (Exception e) {
                log.error("❌ Failed to fetch emails or send class notifications: {}", e.getMessage());
            }
        }
        else if (event.getRecipientEmail() != null) {
            sendEmail(
                    event.getRecipientEmail().toString(),
                    event.getTitle().toString(),
                    event.getContent().toString()
            );
        }
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