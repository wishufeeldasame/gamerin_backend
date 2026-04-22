package com.gamerin.backend.domain.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class PasswordResetMailService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetMailService.class);

    private final JavaMailSender mailSender;
    private final String frontendBaseUrl;
    private final String passwordResetPath;
    private final String fromAddress;
    private final String mailHost;

    public PasswordResetMailService(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${app.frontend.base-url:http://localhost:3000}") String frontendBaseUrl,
            @Value("${app.frontend.password-reset-path:/auth/reset-password}") String passwordResetPath,
            @Value("${app.mail.from-address:no-reply@gamerin.local}") String fromAddress,
            @Value("${spring.mail.host:}") String mailHost
    ) {
        this.mailSender = mailSenderProvider.getIfAvailable();
        this.frontendBaseUrl = frontendBaseUrl;
        this.passwordResetPath = passwordResetPath;
        this.fromAddress = fromAddress;
        this.mailHost = mailHost;
    }

    public void sendPasswordResetMail(String recipientEmail, String resetToken) {
        String resetUrl = UriComponentsBuilder.fromUriString(frontendBaseUrl)
                .path(passwordResetPath)
                .queryParam("token", resetToken)
                .build()
                .toUriString();

        if (mailSender == null || mailHost == null || mailHost.isBlank()) {
            log.info("Password reset link for {}: {}", recipientEmail, resetUrl);
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
        } catch (MailException e) {
            throw new IllegalStateException("비밀번호 재설정 메일 발송에 실패했습니다.", e);
        }
    }
}
