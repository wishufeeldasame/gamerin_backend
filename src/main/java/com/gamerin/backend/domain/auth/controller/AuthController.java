package com.gamerin.backend.domain.auth.controller;

import com.gamerin.backend.domain.auth.dto.request.*;
import com.gamerin.backend.domain.auth.dto.response.*;
import com.gamerin.backend.domain.auth.service.LocalAuthService;
import com.gamerin.backend.domain.auth.service.TokenService;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final LocalAuthService localAuthService;
    private final String refreshCookieName;

    public AuthController(
            LocalAuthService localAuthService,
            @Value("${app.auth.jwt.refresh-cookie-name:refresh_token}") String refreshCookieName
    ) {
        this.localAuthService = localAuthService;
        this.refreshCookieName = refreshCookieName;
    }

    @GetMapping("/availability/handle")
    public ApiResponse<HandleAvailabilityResponse> checkHandleAvailability(@RequestParam("handle") String handle) {
        return ApiResponse.ok(localAuthService.checkHandleAvailability(handle));
    }

    @PostMapping("/signup")
    public ApiResponse<AuthTokenResponse> signUp(@Valid @RequestBody SignUpRequest request, HttpServletResponse response) {
        TokenService.AuthResult result = localAuthService.signUp(request);
        setRefreshTokenCookie(response, result.refreshToken(), result.refreshTokenExpiresIn());
        return ApiResponse.ok(result.authTokenResponse());
    }

    @PostMapping("/social-signup")
    public ApiResponse<AuthTokenResponse> socialSignUp(@Valid @RequestBody SocialSignUpRequest request, HttpServletResponse response) {
        TokenService.AuthResult result = localAuthService.socialSignUp(request);
        setRefreshTokenCookie(response, result.refreshToken(), result.refreshTokenExpiresIn());
        return ApiResponse.ok(result.authTokenResponse());
    }

    @PostMapping("/login")
    public ApiResponse<AuthTokenResponse> login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        TokenService.AuthResult result = localAuthService.login(request);
        setRefreshTokenCookie(response, result.refreshToken(), result.refreshTokenExpiresIn());
        return ApiResponse.ok(result.authTokenResponse());
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthTokenResponse> refresh(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = getRefreshTokenFromCookie(request);
        try {
            TokenService.AuthResult result = localAuthService.refresh(refreshToken);
            setRefreshTokenCookie(response, result.refreshToken(), result.refreshTokenExpiresIn());
            return ApiResponse.ok(result.authTokenResponse());
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                clearRefreshTokenCookie(response);
            }
            throw exception;
        }
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = getRefreshTokenFromCookie(request);
        localAuthService.logout(refreshToken);
        clearRefreshTokenCookie(response);
        return ApiResponse.ok(null);
    }

    @PostMapping("/find-id")
    public ApiResponse<FindIdResponse> findId(@Valid @RequestBody FindIdRequest request) {
        return ApiResponse.ok(localAuthService.findId(request));
    }

    @PostMapping("/find-password")
    public ApiResponse<Void> findPassword(@Valid @RequestBody FindPasswordRequest request) {
        localAuthService.findPassword(request);
        return ApiResponse.ok(null);
    }

    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        localAuthService.resetPassword(request);
        return ApiResponse.ok(null);
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@AuthenticationPrincipal CustomUserPrincipal principal) {
        return ApiResponse.ok(localAuthService.getMe(principal));
    }

    private String getRefreshTokenFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (refreshCookieName.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    private void setRefreshTokenCookie(HttpServletResponse response, String refreshToken, long maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from(refreshCookieName, refreshToken)
                .httpOnly(true).secure(false).path("/").sameSite("Lax").maxAge(maxAgeSeconds).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshTokenCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(refreshCookieName, "")
                .httpOnly(true).secure(false).path("/").sameSite("Lax").maxAge(0).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
