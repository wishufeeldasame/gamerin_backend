package com.gamerin.backend.domain.weeklyreport.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserRole;
import com.gamerin.backend.domain.valorant.model.ValorantRegion;
import com.gamerin.backend.domain.weeklyreport.dto.response.WeeklyGameReportResponse;
import com.gamerin.backend.domain.weeklyreport.model.WeekStats;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyAnalysis;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyReportPeriod;
import com.gamerin.backend.domain.weeklyreport.model.WeeklyStats;
import com.gamerin.backend.domain.weeklyreport.service.WeeklyReportService;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@AutoConfigureMockMvc
class WeeklyReportControllerIntegrationTest {

    private static final String ADMIN_BODY = "{\"riotId\":\"name#KR1\",\"region\":\"KR\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WeeklyReportService weeklyReportService;

    @Test
    void unauthenticatedRequestsReturnJsonUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/memberships/weekly-report"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(post("/api/v1/admin/weekly-reports/valorant")
                        .contentType(MediaType.APPLICATION_JSON).content(ADMIN_BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(weeklyReportService);
    }

    @Test
    void nonMemberGetsForbiddenJson() throws Exception {
        CustomUserPrincipal principal = principal(UserRole.USER);
        when(weeklyReportService.getMyReport(principal.getUserId()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "멤버십 전용 콘텐츠입니다."));

        mockMvc.perform(get("/api/v1/memberships/weekly-report").with(user(principal)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("멤버십 전용 콘텐츠입니다."));
    }

    @Test
    void memberGetsReportInFrontendShape() throws Exception {
        CustomUserPrincipal principal = principal(UserRole.USER);
        when(weeklyReportService.getMyReport(principal.getUserId())).thenReturn(report());

        mockMvc.perform(get("/api/v1/memberships/weekly-report").with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.weekStart").value("2026-11-02"))
                .andExpect(jsonPath("$.data.weekEnd").value("2026-11-08"))
                .andExpect(jsonPath("$.data.previousWeekStart").value("2026-10-26"))
                .andExpect(jsonPath("$.data.previousWeekEnd").value("2026-11-01"))
                .andExpect(jsonPath("$.data.totalMatches").value(12))
                .andExpect(jsonPath("$.data.games[0].game").value("VALORANT"))
                .andExpect(jsonPath("$.data.games[0].gameLabel").value("발로란트"))
                .andExpect(jsonPath("$.data.games[0].matches").value(12))
                .andExpect(jsonPath("$.data.games[0].winRate").value(50.0))
                .andExpect(jsonPath("$.data.games[0].kd").value(1.1))
                .andExpect(jsonPath("$.data.games[0].headshotRate").value(24.6))
                .andExpect(jsonPath("$.data.games[0].tierLabel").value("골드 1"))
                .andExpect(jsonPath("$.data.games[0].previousWeek").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.games[0].analysis.headline").value("제목"))
                .andExpect(jsonPath("$.data.games[0].analysis.summary").value("요약"))
                .andExpect(jsonPath("$.data.games[0].analysis.focus").value("점검"))
                // 집계 전용 값은 응답에 싣지 않는다.
                .andExpect(jsonPath("$.data.games[0].acs").doesNotExist())
                .andExpect(jsonPath("$.data.games[0].maps").doesNotExist());
    }

    @Test
    void adminGenerationRequiresAdminRole() throws Exception {
        mockMvc.perform(post("/api/v1/admin/weekly-reports/valorant").with(user(principal(UserRole.USER)))
                        .contentType(MediaType.APPLICATION_JSON).content(ADMIN_BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(weeklyReportService);

        when(weeklyReportService.generateForRiotId("name#KR1", ValorantRegion.KR)).thenReturn(report());
        mockMvc.perform(post("/api/v1/admin/weekly-reports/valorant").with(user(principal(UserRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content(ADMIN_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.games[0].matches").value(12));

        mockMvc.perform(post("/api/v1/admin/weekly-reports/valorant").with(user(principal(UserRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"riotId\":\"\",\"region\":\"KR\"}"))
                .andExpect(status().isBadRequest());
    }

    private WeeklyGameReportResponse report() {
        return WeeklyGameReportResponse.valorant(new WeeklyReportPeriod(LocalDate.parse("2026-11-02")),
                new WeeklyStats(new WeekStats(12, 6, 50.0, 1.1, 24.6, 210, "골드 1", List.of(), List.of()), null, null),
                new WeeklyAnalysis("제목", "요약", "점검"));
    }

    private CustomUserPrincipal principal(UserRole role) {
        User user = User.createLocal("member@example.com", "member", "Member", "encoded-password");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(user, "role", role);
        return CustomUserPrincipal.from(user);
    }
}
