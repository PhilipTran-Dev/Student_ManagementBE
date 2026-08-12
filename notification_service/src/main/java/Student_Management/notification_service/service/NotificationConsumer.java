package Student_Management.notification_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import Student_Management.event.NotificationEvent;
@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationConsumer {

    private final JavaMailSender mailSender;

    @KafkaListener(topics = "notification-events-topic", groupId = "notification-group")
    public void consume(NotificationEvent event) {
        log.info("📩 [KAFKA RECEIVED] New notification event received:");
        log.info("   📬 Recipient: {}", event.getRecipientEmail());
        log.info("   📌 Title: {}", event.getTitle());
        log.info("   📝 Content: {}", event.getContent());
        log.info("   🏷️ Event Type: {}", event.getEventType());

        // Call method to send actual email
        sendEmail(
                event.getRecipientEmail().toString(),
                event.getTitle().toString(),
                event.getContent().toString()
        );
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