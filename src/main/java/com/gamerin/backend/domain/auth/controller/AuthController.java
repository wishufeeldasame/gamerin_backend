package com.gamerin.backend.domain.auth.controller;

import com.gamerin.backend.domain.auth.dto.request.LoginRequest;
import com.gamerin.backend.domain.auth.dto.request.SignUpRequest;
import com.gamerin.backend.domain.auth.dto.request.SocialSignUpRequest;
import com.gamerin.backend.domain.auth.dto.response.AuthTokenResponse;
import com.gamerin.backend.domain.auth.dto.response.HandleAvailabilityResponse;
import com.gamerin.backend.domain.auth.dto.response.MeResponse;
import com.gamerin.backend.domain.auth.service.LocalAuthService;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.gamerin.backend.domain.auth.dto.request.FindIdRequest;
import com.gamerin.backend.domain.auth.dto.response.FindIdResponse;
import com.gamerin.backend.domain.auth.dto.request.FindPasswordRequest;
import com.gamerin.backend.domain.auth.dto.request.ResetPasswordRequest;

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
    public ApiResponse<AuthTokenResponse> signUp(
            @Valid @RequestBody SignUpRequest request,
            HttpServletResponse response
    ) {
        LocalAuthService.AuthResult result = localAuthService.signUp(request);
        setRefreshTokenCookie(response, result.refreshToken(), result.refreshTokenExpiresIn());
        return ApiResponse.ok(result.authTokenResponse());
    }

    @PostMapping("/social-signup")
    public ApiResponse<AuthTokenResponse> socialSignUp(
            @Valid @RequestBody SocialSignUpRequest request,
            HttpServletResponse response
    ) {
        LocalAuthService.AuthResult result = localAuthService.socialSignUp(request);
        setRefreshTokenCookie(response, result.refreshToken(), result.refreshTokenExpiresIn()); 
        return ApiResponse.ok(result.authTokenResponse());
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

    @PostMapping("/login")
    public ApiResponse<AuthTokenResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response
    ) {
        LocalAuthService.AuthResult result = localAuthService.login(request);
        setRefreshTokenCookie(response, result.refreshToken(), result.refreshTokenExpiresIn());
        return ApiResponse.ok(result.authTokenResponse());
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthTokenResponse> refresh(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        String refreshToken = getRefreshTokenFromCookie(request);
        LocalAuthService.AuthResult result = localAuthService.refresh(refreshToken);
        setRefreshTokenCookie(response, result.refreshToken(), result.refreshTokenExpiresIn());
        return ApiResponse.ok(result.authTokenResponse());
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        String refreshToken = getRefreshTokenFromCookie(request);
        localAuthService.logout(refreshToken);
        clearRefreshTokenCookie(response);
        return ApiResponse.ok(null);
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@AuthenticationPrincipal CustomUserPrincipal principal) {
        return ApiResponse.ok(localAuthService.getMe(principal));
    }


    private String getRefreshTokenFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (refreshCookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void setRefreshTokenCookie(HttpServletResponse response, String refreshToken, long maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from(refreshCookieName, refreshToken)
                .httpOnly(true)
                .secure(false)
                .path("/")
                .sameSite("Lax")
                .maxAge(maxAgeSeconds)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshTokenCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(refreshCookieName, "")
                .httpOnly(true)
                .secure(false)
                .path("/")
                .sameSite("Lax")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
