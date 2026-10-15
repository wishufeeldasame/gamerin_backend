package com.gamerin.backend.domain.auth.service;

import java.util.Map;

import com.gamerin.backend.global.logging.JsonConsoleLogger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailVerificationMailService {

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String mailHost;
    private final boolean consoleFallbackEnabled;

    public EmailVerificationMailService(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${app.mail.from-address:no-reply@gamerin.local}") String fromAddress,
            @Value("${spring.mail.host:}") String mailHost,
            Environment environment
    ) {
        this.mailSender = mailSenderProvider.getIfAvailable();
        this.fromAddress = fromAddress;
        this.mailHost = mailHost;
        // 인증번호 원문 로그는 로컬 개발에서만 허용한다
        this.consoleFallbackEnabled = environment.acceptsProfiles(Profiles.of("local"));
    }

    public void sendSignupVerificationCode(String recipientEmail, String code) {
        if (mailSender == null || mailHost == null || mailHost.isBlank()) {
            if (!consoleFallbackEnabled) {
                JsonConsoleLogger.failure("auth.email_verification_mail.sent", "mail_sender_not_configured", Map.of(
                        "recipientEmail", maskEmail(recipientEmail)
                ));
                throw new IllegalStateException("이메일 발송 설정이 없어 인증번호를 보낼 수 없습니다.");
            }
            JsonConsoleLogger.success("auth.email_verification_code.generated", Map.of(
                    "delivery", "console",
                    "reason", "mail_sender_not_configured",
                    "recipientEmail", maskEmail(recipientEmail),
                    "code", code
            ));
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(recipientEmail);
        message.setFrom(fromAddress);
        message.setSubject("[GamerIN] 회원가입 이메일 인증번호");
        message.setText("""
                GamerIN 회원가입 인증번호입니다.

                인증번호: %s

                인증번호는 5분 동안 유효합니다.
                본인이 요청하지 않았다면 이 메일을 무시하셔도 됩니다.
                """.formatted(code));

        try {
            mailSender.send(message);
            JsonConsoleLogger.success("auth.email_verification_mail.sent", Map.of(
                    "recipientEmail", maskEmail(recipientEmail)
            ));
        } catch (MailException e) {
            JsonConsoleLogger.failure("auth.email_verification_mail.sent", "mail_send_failed", Map.of(
                    "recipientEmail", maskEmail(recipientEmail),
                    "exception", e.getClass().getSimpleName()
            ));
            throw new IllegalStateException("이메일 인증번호 발송에 실패했습니다.", e);
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
