package com.gamerin.backend.domain.auth.controller;

import com.gamerin.backend.domain.auth.dto.request.FindIdRequest;
import com.gamerin.backend.domain.auth.dto.request.FindPasswordRequest;
import com.gamerin.backend.domain.auth.dto.request.ResetPasswordRequest;
import com.gamerin.backend.domain.auth.dto.request.SignUpRequest;
import com.gamerin.backend.domain.auth.dto.response.AuthTokenResponse;
import com.gamerin.backend.domain.auth.dto.response.FindIdResponse;
import com.gamerin.backend.domain.auth.dto.response.MeResponse;
import com.gamerin.backend.domain.auth.service.LocalAuthService;
import com.gamerin.backend.domain.auth.service.TokenService;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private LocalAuthService localAuthService;

    private AuthController authController;

    @BeforeEach
    void setUp() {
        authController = new AuthController(localAuthService, "refresh_token");
    }

    @Test
    void signUpSetsRefreshTokenCookie() {
        SignUpRequest request = new SignUpRequest(
                "tester",
                "Tester",
                "user@example.com",
                "Password1!",
                "Password1!",
                true,
                true
        );
        TokenService.AuthResult authResult = authResult(UUID.randomUUID(), "tester", "Tester");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(localAuthService.signUp(request)).thenReturn(authResult);

        ApiResponse<AuthTokenResponse> apiResponse = authController.signUp(request, response);

        assertThat(apiResponse.data()).isEqualTo(authResult.authTokenResponse());
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                .contains("refresh_token=refresh-token")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .contains("Max-Age=1209600");
    }

    @Test
    void refreshReadsCookieAndRotatesRefreshToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("refresh_token", "old-refresh-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        TokenService.AuthResult authResult = authResult(UUID.randomUUID(), "tester", "Tester");

        when(localAuthService.refresh("old-refresh-token")).thenReturn(authResult);

        ApiResponse<AuthTokenResponse> apiResponse = authController.refresh(request, response);

        verify(localAuthService).refresh("old-refresh-token");
        assertThat(apiResponse.data()).isEqualTo(authResult.authTokenResponse());
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("refresh_token=refresh-token");
    }

    @Test
    void refreshClearsCookieWhenRefreshTokenIsUnauthorized() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("refresh_token", "stale-refresh-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(localAuthService.refresh("stale-refresh-token"))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 리프레시 토큰입니다."));

        assertThatThrownBy(() -> authController.refresh(request, response))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                .contains("refresh_token=")
                .contains("Max-Age=0");
    }

    @Test
    void logoutClearsCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("refresh_token", "logout-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        ApiResponse<Void> apiResponse = authController.logout(request, response);

        verify(localAuthService).logout("logout-token");
        assertThat(apiResponse.success()).isTrue();
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
                .contains("refresh_token=")
                .contains("Max-Age=0");
    }

    @Test
    void findIdReturnsServiceResponse() {
        FindIdRequest request = new FindIdRequest("user@example.com");
        FindIdResponse findIdResponse = new FindIdResponse("abc***", OffsetDateTime.parse("2026-04-20T10:15:30+09:00"));

        when(localAuthService.findId(request)).thenReturn(findIdResponse);

        ApiResponse<FindIdResponse> response = authController.findId(request);

        assertThat(response.data()).isEqualTo(findIdResponse);
    }

    @Test
    void findPasswordDelegatesToService() {
        FindPasswordRequest request = new FindPasswordRequest("tester");

        ApiResponse<Void> response = authController.findPassword(request);

        verify(localAuthService).findPassword(request);
        assertThat(response.success()).isTrue();
    }

    @Test
    void resetPasswordDelegatesTokenBasedRequestToService() {
        ResetPasswordRequest request = new ResetPasswordRequest("reset-token", "NewPassword1!", "NewPassword1!");

        ApiResponse<Void> response = authController.resetPassword(request);

        verify(localAuthService).resetPassword(request);
        assertThat(response.success()).isTrue();
    }

    @Test
    void meReturnsAuthenticatedUserPayload() {
        UUID userId = UUID.randomUUID();
        var user = com.gamerin.backend.domain.user.entity.User.createLocal(
                "user@example.com",
                "tester",
                "Tester",
                "encoded-password"
        );
        ReflectionTestUtils.setField(user, "id", userId);
        CustomUserPrincipal principal = CustomUserPrincipal.from(user);
        MeResponse meResponse = new MeResponse(userId, "tester", "Tester", "ROLE_USER", "ACTIVE");

        when(localAuthService.getMe(principal)).thenReturn(meResponse);

        ApiResponse<MeResponse> response = authController.me(principal);

        assertThat(response.data()).isEqualTo(meResponse);
    }

    private TokenService.AuthResult authResult(UUID userId, String handle, String nickname) {
        return new TokenService.AuthResult(
                new AuthTokenResponse(userId, handle, nickname, "access-token", 1800L),
                "refresh-token",
                1209600L
        );
    }
}
