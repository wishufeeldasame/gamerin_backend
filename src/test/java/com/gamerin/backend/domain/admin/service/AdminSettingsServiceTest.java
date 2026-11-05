package com.gamerin.backend.domain.admin.service;

import com.gamerin.backend.domain.admin.dto.response.SystemConfigResponse;
import com.gamerin.backend.domain.admin.entity.AdminAuditLog;
import com.gamerin.backend.domain.admin.entity.SystemConfig;
import com.gamerin.backend.domain.admin.repository.AdminAuditLogRepository;
import com.gamerin.backend.domain.admin.repository.SystemConfigRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminSettingsServiceTest {

    @Mock
    private SystemConfigRepository systemConfigRepository;

    @Mock
    private AdminAuditLogRepository adminAuditLogRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AdminSettingsService adminSettingsService;

    private User admin;
    private UUID adminId;

    @BeforeEach
    void setUp() {
        adminId = UUID.randomUUID();
        admin = User.createLocal("admin@gamerin.com", "admin01", "최고관리자", "encodedPw");
        ReflectionTestUtils.setField(admin, "id", adminId);
    }

    @Test
    @DisplayName("전체 시스템 설정 목록을 조회할 수 있다")
    void getAllConfigs_success() {
        // given
        SystemConfig c1 = SystemConfig.create("AUTO_HIDE_ENABLED", "true", "자동 숨김 활성화");
        SystemConfig c2 = SystemConfig.create("AUTO_HIDE_THRESHOLD", "5", "자동 숨김 임계값");
        given(systemConfigRepository.findAll()).willReturn(List.of(c1, c2));

        // when
        List<SystemConfigResponse> result = adminSettingsService.getAllConfigs();

        // then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).configKey()).isEqualTo("AUTO_HIDE_ENABLED");
        assertThat(result.get(1).configValue()).isEqualTo("5");
    }

    @Test
    @DisplayName("단건 설정값 수정 시 값이 갱신되고 감사 로그가 적재된다")
    void updateConfig_success() {
        // given
        SystemConfig config = SystemConfig.create("AUTO_HIDE_THRESHOLD", "5", "자동 숨김 임계값");
        given(userRepository.findById(adminId)).willReturn(Optional.of(admin));
        given(systemConfigRepository.findByConfigKey("AUTO_HIDE_THRESHOLD")).willReturn(Optional.of(config));
        given(systemConfigRepository.save(any(SystemConfig.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        SystemConfigResponse response = adminSettingsService.updateConfig(adminId, "AUTO_HIDE_THRESHOLD", "10");

        // then
        assertThat(response.configValue()).isEqualTo("10");
        assertThat(config.getConfigValue()).isEqualTo("10");

        // 감사 로그가 적재되었는지 확인
        verify(adminAuditLogRepository, times(1)).save(any(AdminAuditLog.class));
    }

    @Test
    @DisplayName("다건 설정값을 일괄 수정할 수 있다")
    void updateMultipleConfigs_success() {
        // given
        SystemConfig c1 = SystemConfig.create("AUTO_HIDE_ENABLED", "true", "설명1");
        SystemConfig c2 = SystemConfig.create("AUTO_HIDE_THRESHOLD", "5", "설명2");

        given(userRepository.findById(adminId)).willReturn(Optional.of(admin));
        // 서비스는 findByConfigKeyIn으로 일괄 조회하므로 anyCollection() stub 사용
        given(systemConfigRepository.findByConfigKeyIn(any())).willReturn(List.of(c1, c2));
        given(systemConfigRepository.findAll()).willReturn(List.of(c1, c2));

        Map<String, String> updateMap = Map.of(
                "AUTO_HIDE_ENABLED", "false",
                "AUTO_HIDE_THRESHOLD", "8");

        // when
        List<SystemConfigResponse> responses = adminSettingsService.updateMultipleConfigs(adminId, updateMap);

        // then
        assertThat(responses).hasSize(2);
        assertThat(c1.getConfigValue()).isEqualTo("false");
        assertThat(c2.getConfigValue()).isEqualTo("8");

        // 감사 로그가 2회 적재되었는지 확인
        verify(adminAuditLogRepository, times(2)).save(any(AdminAuditLog.class));
    }

    @Test
    @DisplayName("존재하지 않는 설정 키 수정 시 404 예외가 발생한다")
    void updateConfig_notFound() {
        // given
        given(userRepository.findById(adminId)).willReturn(Optional.of(admin));
        given(systemConfigRepository.findByConfigKey("NON_EXIST_KEY")).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> adminSettingsService.updateConfig(adminId, "NON_EXIST_KEY", "value"))
                .isInstanceOf(ResponseStatusException.class);
    }
}