package com.gamerin.backend.domain.membership.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.membership.dto.response.MyMembershipResponse;
import com.gamerin.backend.domain.membership.service.MembershipService;
import com.gamerin.backend.domain.payment.model.PaymentProduct;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@SpringBootTest
@AutoConfigureMockMvc
class MembershipControllerIntegrationTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-15T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MembershipService membershipService;

    @Test
    void unauthenticatedRequestReturnsJsonUnauthorizedWithoutOAuthRedirect() throws Exception {
        mockMvc.perform(get("/api/v1/memberships/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(membershipService);
    }

    @Test
    void returnsAuthenticatedUsersMembershipIgnoringOtherUserIdParameter() throws Exception {
        CustomUserPrincipal principal = principal();
        OffsetDateTime expiresAt = NOW.plusDays(5);
        when(membershipService.getMyMembership(principal.getUserId()))
                .thenReturn(MyMembershipResponse.from(expiresAt, NOW));

        MvcResult result = mockMvc.perform(get("/api/v1/memberships/me")
                        .with(user(principal))
                        .param("userId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.price").value(PaymentProduct.MEMBERSHIP_30D.getPrice()))
                .andExpect(jsonPath("$.data.durationDays").value(PaymentProduct.MEMBERSHIP_30D.getDurationDays()))
                .andExpect(jsonPath("$.data.benefits", hasSize(3)))
                .andReturn();

        assertThat(OffsetDateTime.parse(body(result).path("data").path("expiresAt").asText()))
                .isEqualTo(expiresAt);
        verify(membershipService).getMyMembership(principal.getUserId());
        verifyNoMoreInteractions(membershipService);
    }

    @Test
    void returnsExpiredMembershipWithoutDiscardingExpiration() throws Exception {
        CustomUserPrincipal principal = principal();
        OffsetDateTime expiresAt = NOW.minusDays(1);
        when(membershipService.getMyMembership(principal.getUserId()))
                .thenReturn(MyMembershipResponse.from(expiresAt, NOW));

        MvcResult result = mockMvc.perform(get("/api/v1/memberships/me").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false))
                .andReturn();

        assertThat(OffsetDateTime.parse(body(result).path("data").path("expiresAt").asText()))
                .isEqualTo(expiresAt);
        verify(membershipService).getMyMembership(principal.getUserId());
        verifyNoMoreInteractions(membershipService);
    }

    @Test
    void neverSubscribedUserReceivesNullExpirationAndSharedProductInformation() throws Exception {
        CustomUserPrincipal principal = principal();
        when(membershipService.getMyMembership(principal.getUserId()))
                .thenReturn(MyMembershipResponse.from(null, NOW));

        MvcResult result = mockMvc.perform(get("/api/v1/memberships/me").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false))
                .andExpect(jsonPath("$.data.expiresAt").value(nullValue()))
                .andExpect(jsonPath("$.data.price").value(PaymentProduct.MEMBERSHIP_30D.getPrice()))
                .andExpect(jsonPath("$.data.durationDays").value(PaymentProduct.MEMBERSHIP_30D.getDurationDays()))
                .andExpect(jsonPath("$.data.benefits", hasSize(3)))
                .andReturn();

        assertThat(body(result).path("data").has("expiresAt")).isTrue();
        verify(membershipService).getMyMembership(principal.getUserId());
        verifyNoMoreInteractions(membershipService);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private CustomUserPrincipal principal() {
        User member = User.createLocal("member@example.com", "member", "Member", "encoded-password");
        ReflectionTestUtils.setField(member, "id", UUID.randomUUID());
        return CustomUserPrincipal.from(member);
    }
}
