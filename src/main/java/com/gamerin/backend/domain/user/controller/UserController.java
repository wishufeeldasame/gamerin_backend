package com.gamerin.backend.domain.user.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gamerin.backend.domain.user.dto.response.UserProfileResponse;
import com.gamerin.backend.domain.user.service.UserService;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public ApiResponse<UserProfileResponse> getMyProfile(
        @AuthenticationPrincipal CustomUserPrincipal principal
    ) {
    // 서비스에서 프로필 정보를 가져옵니다.
    UserProfileResponse response = userService.getMyProfile(principal.getUserId());
    return ApiResponse.ok(response);
    }
}
