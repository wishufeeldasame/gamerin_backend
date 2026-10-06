package com.gamerin.backend.domain.admin.service;

import com.gamerin.backend.domain.admin.dto.response.SystemConfigResponse;
import com.gamerin.backend.domain.admin.entity.AdminAuditLog;
import com.gamerin.backend.domain.admin.entity.SystemConfig;
import com.gamerin.backend.domain.admin.repository.AdminAuditLogRepository;
import com.gamerin.backend.domain.admin.repository.SystemConfigRepository;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AdminSettingsService {

    private final SystemConfigRepository systemConfigRepository;
    private final AdminAuditLogRepository adminAuditLogRepository;
    private final UserRepository userRepository;

    public AdminSettingsService(
            SystemConfigRepository systemConfigRepository,
            AdminAuditLogRepository adminAuditLogRepository,
            UserRepository userRepository) {
        this.systemConfigRepository = systemConfigRepository;
        this.adminAuditLogRepository = adminAuditLogRepository;
        this.userRepository = userRepository;
    }

    /**
     * 전체 시스템 설정 목록 조회
     */
    public List<SystemConfigResponse> getAllConfigs() {
        return systemConfigRepository.findAll().stream()
                .map(SystemConfigResponse::from)
                .toList();
    }

    /**
     * 단건 시스템 설정값 수정
     */
    @Transactional
    public SystemConfigResponse updateConfig(UUID adminId, String configKey, String newValue) {
        User admin = userRepository.findById(adminId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "어드민 유저 정보를 찾을 수 없습니다."));

        SystemConfig config = systemConfigRepository.findByConfigKey(configKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "설정 키를 찾을 수 없습니다: " +
                        configKey));

        String oldValue = config.getConfigValue();
        config.updateValue(newValue);
        SystemConfig saved = systemConfigRepository.save(config);

        // 감사 로그 적재
        adminAuditLogRepository.save(AdminAuditLog.create(
                admin,
                "SYSTEM_CONFIG_UPDATE",
                ReportTargetType.USER, // 시스템 설정은 별도 TargetType이 없으므로 USER 또는 기본 식별자 활용
                admin.getId(),
                null,
                String.format("시스템 설정 변경 [%s: %s -> %s]", configKey, oldValue, newValue)));

        return SystemConfigResponse.from(saved);
    }

    /**
     * 다건 시스템 설정값 일괄 수정 (프론트엔드 일괄 저장 화면 대응)
     */
    @Transactional
    public List<SystemConfigResponse> updateMultipleConfigs(UUID adminId, Map<String, String> configs) {
        User admin = userRepository.findById(adminId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "어드민 유저 정보를 찾을 수 없습니다."));

        for (Map.Entry<String, String> entry : configs.entrySet()) {
            String key = entry.getKey();
            String newValue = entry.getValue();

            systemConfigRepository.findByConfigKey(key).ifPresent(config -> {
                String oldValue = config.getConfigValue();
                config.updateValue(newValue);
                systemConfigRepository.save(config);

                adminAuditLogRepository.save(AdminAuditLog.create(
                        admin,
                        "SYSTEM_CONFIG_UPDATE",
                        ReportTargetType.USER,
                        admin.getId(),
                        null,
                        String.format("시스템 설정 변경 [%s: %s -> %s]", key, oldValue, newValue)));
            });
        }

        return getAllConfigs();
    }
}