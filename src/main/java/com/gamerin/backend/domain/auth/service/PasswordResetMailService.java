package com.gamerin.backend.domain.auth.service;

import java.util.Map;

import com.gamerin.backend.global.logging.JsonConsoleLogger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class PasswordResetMailService {

    private final JavaMailSender mailSender;
    private final String frontendBaseUrl;
    private final String passwordResetPath;
    private final String fromAddress;
    private final String mailHost;
    private final boolean consoleFallbackEnabled;

    public PasswordResetMailService(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${app.frontend.base-url:http://localhost:3000}") String frontendBaseUrl,
            @Value("${app.frontend.password-reset-path:/auth/reset-password}") String passwordResetPath,
            @Value("${app.mail.from-address:no-reply@gamerin.local}") String fromAddress,
            @Value("${spring.mail.host:}") String mailHost,
            Environment environment
    ) {
        this.mailSender = mailSenderProvider.getIfAvailable();
        this.frontendBaseUrl = frontendBaseUrl;
        this.passwordResetPath = passwordResetPath;
        this.fromAddress = fromAddress;
        this.mailHost = mailHost;
        // 토큰이 포함된 재설정 링크 로그는 로컬 개발에서만 허용한다
        this.consoleFallbackEnabled = environment.acceptsProfiles(Profiles.of("local"));
    }

    public void sendPasswordResetMail(String recipientEmail, String resetToken) {
        String resetUrl = UriComponentsBuilder.fromUriString(frontendBaseUrl)
                .path(passwordResetPath)
                .queryParam("token", resetToken)
                .build()
                .toUriString();

        if (mailSender == null || mailHost == null || mailHost.isBlank()) {
            if (!consoleFallbackEnabled) {
                JsonConsoleLogger.failure("auth.password_reset_mail.sent", "mail_sender_not_configured", Map.of(
                        "recipientEmail", maskEmail(recipientEmail)
                ));
                throw new IllegalStateException("이메일 발송 설정이 없어 비밀번호 재설정 메일을 보낼 수 없습니다.");
            }
            JsonConsoleLogger.success("auth.password_reset_link.generated", Map.of(
                    "delivery", "console",
                    "reason", "mail_sender_not_configured",
                    "recipientEmail", maskEmail(recipientEmail),
                    "resetUrl", resetUrl
            ));
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(recipientEmail);
        message.setFrom(fromAddress);
        message.setSubject("[GamerIN] 비밀번호 재설정 안내");
        message.setText("""
                비밀번호 재설정을 요청하셨습니다.

                아래 링크에서 새 비밀번호를 설정해주세요.
                %s

                본인이 요청하지 않았다면 이 메일을 무시하셔도 됩니다.
                """.formatted(resetUrl));

        try {
            mailSender.send(message);
            JsonConsoleLogger.success("auth.password_reset_mail.sent", Map.of(
                    "recipientEmail", maskEmail(recipientEmail)
            ));
        } catch (MailException e) {
            JsonConsoleLogger.failure("auth.password_reset_mail.sent", "mail_send_failed", Map.of(
                    "recipientEmail", maskEmail(recipientEmail),
                    "exception", e.getClass().getSimpleName()
            ));
            throw new IllegalStateException("비밀번호 재설정 메일 발송에 실패했습니다.", e);
        }
    }

    private String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }

        int atIndex = email.indexOf('@');
        if (atIndex <= 1) {
            return "***" + (atIndex >= 0 ? email.substring(atIndex) : "");
        }

        return email.charAt(0) + "***" + email.substring(atIndex);
    }
}
