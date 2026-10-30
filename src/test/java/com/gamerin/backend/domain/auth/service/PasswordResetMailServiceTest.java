package com.gamerin.backend.domain.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PasswordResetMailServiceTest {

    private static final String TOKEN = "raw-reset-token-abc123";

    @Test
    void localProfileFallsBackToConsoleWithoutSmtp() {
        PasswordResetMailService service = service(null, "", "local");

        assertThatCode(() -> service.sendPasswordResetMail("user@example.com", TOKEN))
                .doesNotThrowAnyException();
    }

    @Test
    void nonLocalProfileFailsWithoutSmtpAndDoesNotLeakToken() {
        PasswordResetMailService service = service(null, "", "prod");

        assertThatThrownBy(() -> service.sendPasswordResetMail("user@example.com", TOKEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(TOKEN);
    }

    @Test
    void configuredSmtpSendsMailRegardlessOfProfile() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        PasswordResetMailService service = service(mailSender, "smtp.example.com", "prod");

        service.sendPasswordResetMail("user@example.com", TOKEN);

        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    private PasswordResetMailService service(JavaMailSender mailSender, String mailHost, String profile) {
        StaticListableBeanFactory beanFactory = mailSender == null
                ? new StaticListableBeanFactory()
                : new StaticListableBeanFactory(Map.of("mailSender", mailSender));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return new PasswordResetMailService(
                beanFactory.getBeanProvider(JavaMailSender.class), "http://localhost:3000",
                "/auth/reset-password", "no-reply@gamerin.test", mailHost, environment);
    }
}
