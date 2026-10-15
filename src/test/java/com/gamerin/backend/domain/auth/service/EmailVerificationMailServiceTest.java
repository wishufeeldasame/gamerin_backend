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

class EmailVerificationMailServiceTest {

    @Test
    void localProfileFallsBackToConsoleWithoutSmtp() {
        EmailVerificationMailService service = service(null, "", "local");

        assertThatCode(() -> service.sendSignupVerificationCode("user@example.com", "123456"))
                .doesNotThrowAnyException();
    }

    @Test
    void nonLocalProfileFailsWithoutSmtp() {
        EmailVerificationMailService service = service(null, "", "prod");

        assertThatThrownBy(() -> service.sendSignupVerificationCode("user@example.com", "123456"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void configuredSmtpSendsMailRegardlessOfProfile() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        EmailVerificationMailService service = service(mailSender, "smtp.example.com", "prod");

        service.sendSignupVerificationCode("user@example.com", "123456");

        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    private EmailVerificationMailService service(JavaMailSender mailSender, String mailHost, String profile) {
        StaticListableBeanFactory beanFactory = mailSender == null
                ? new StaticListableBeanFactory()
                : new StaticListableBeanFactory(Map.of("mailSender", mailSender));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return new EmailVerificationMailService(
                beanFactory.getBeanProvider(JavaMailSender.class), "no-reply@gamerin.test", mailHost, environment);
    }
}
