package com.gamerin.backend.domain.r6.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Set;

import com.gamerin.backend.domain.r6.dto.request.R6ConnectRequest;
import com.gamerin.backend.domain.r6.dto.response.R6ConnectionResponse;
import com.gamerin.backend.domain.r6.dto.response.R6SummaryResponse;
import com.gamerin.backend.domain.r6.service.R6Service;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class R6ControllerTest {

    @Mock
    private R6Service r6Service;

    private R6Controller r6Controller;
    private Validator validator;

    @BeforeEach
    void setUp() {
        r6Controller = new R6Controller(r6Service);
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void connectDelegatesPlayerNameOnlyRequestAndReturnsApiResponse() {
        CustomUserPrincipal principal = principal();
        R6ConnectRequest request = new R6ConnectRequest("R6Player");
        R6ConnectionResponse serviceResponse = new R6ConnectionResponse(true, "R6Player", "PC");
        when(r6Service.connect(principal, request)).thenReturn(serviceResponse);

        ApiResponse<R6ConnectionResponse> response = r6Controller.connect(principal, request);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo(serviceResponse);
        assertThat(response.data().platform()).isEqualTo("PC");
    }

    @Test
    void getMyR6SummaryReturnsApiResponse() {
        CustomUserPrincipal principal = principal();
        R6SummaryResponse serviceResponse = new R6SummaryResponse(
                "R6",
                true,
                "R6Player",
                "PC",
                "Gold",
                1.2,
                52.0,
                100,
                OffsetDateTime.parse("2026-07-10T12:00:00+09:00")
        );
        when(r6Service.getMySummary(principal)).thenReturn(serviceResponse);

        ApiResponse<R6SummaryResponse> response = r6Controller.getMyR6Summary(principal);

        verify(r6Service).getMySummary(principal);
        assertThat(response.success()).isTrue();
        assertThat(response.data().platform()).isEqualTo("PC");
        assertThat(response.data()).isEqualTo(serviceResponse);
    }

    @Test
    void refreshMyR6SummaryDelegatesToServiceAndReturnsApiResponse() {
        CustomUserPrincipal principal = principal();
        R6SummaryResponse serviceResponse = new R6SummaryResponse(
                "R6",
                true,
                "R6Player",
                "PC",
                "Platinum",
                1.45,
                59.0,
                130,
                OffsetDateTime.parse("2026-07-10T12:00:00+09:00")
        );
        when(r6Service.refreshMySummary(principal)).thenReturn(serviceResponse);

        ApiResponse<R6SummaryResponse> response = r6Controller.refreshMyR6Summary(principal);

        verify(r6Service).refreshMySummary(principal);
        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo(serviceResponse);
    }

    @Test
    void disconnectDelegatesToService() {
        CustomUserPrincipal principal = principal();

        ApiResponse<Void> response = r6Controller.disconnect(principal);

        verify(r6Service).disconnect(principal);
        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNull();
    }

    @Test
    void connectRequestValidatesPlayerNameOnlyPayload() {
        assertThat(validate(new R6ConnectRequest("R6Player"))).isEmpty();
        assertThat(validate(new R6ConnectRequest(" "))).isNotEmpty();
        assertThat(validate(new R6ConnectRequest("a".repeat(101)))).isNotEmpty();
    }

    private Set<ConstraintViolation<R6ConnectRequest>> validate(R6ConnectRequest request) {
        return validator.validate(request);
    }

    private CustomUserPrincipal principal() {
        User user = User.createLocal("tester@example.com", "tester", "Tester", "encoded-password");
        return CustomUserPrincipal.from(user);
    }
}
