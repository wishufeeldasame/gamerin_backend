package com.gamerin.backend.domain.membership.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gamerin.backend.domain.membership.dto.response.MyMembershipResponse;
import com.gamerin.backend.domain.membership.service.MembershipService;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Membership", description = "멤버십 이용권 API")
@RestController
@RequestMapping("/api/v1/memberships")
@SecurityRequirement(name = "bearerAuth")
public class MembershipController {

    private final MembershipService membershipService;

    public MembershipController(MembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @Operation(summary = "내 멤버십 조회", description = "본인의 멤버십 상태와 만료일, 이용권 가격·기간 및 혜택을 조회합니다.")
    @GetMapping("/me")
    public ApiResponse<MyMembershipResponse> getMyMembership(
            @AuthenticationPrincipal CustomUserPrincipal principal
    ) {
        return ApiResponse.ok(membershipService.getMyMembership(principal.getUserId()));
    }
}
