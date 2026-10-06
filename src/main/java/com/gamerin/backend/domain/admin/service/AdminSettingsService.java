package com.gamerin.backend.domain.admin.service;

import com.gamerin.backend.domain.admin.dto.response.SystemConfigResponse;
import com.gamerin.backend.domain.admin.entity.AdminAuditLog;
import com.gamerin.backend.domain.admin.entity.SystemConfig;
import com.gamerin.backend.domain.admin.repository.AdminAuditLogRepository;
import com.gamerin.backend.domain.admin.repository.SystemConfigRepository;
import com.gamerin.backend.domain.report.entity.ReportTargetType;
import com.gamerin.backend.domain.report.entity.PenaltyType;
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
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "설정 키를 찾을 수 없습니다: " + configKey));
    
            // [추가] 값 유효성 검증
            validateConfigValue(configKey, newValue);
    
            String oldValue = config.getConfigValue();
            config.updateValue(newValue.trim());
            SystemConfig saved = systemConfigRepository.save(config);
    
            // 감사 로그 적재
            adminAuditLogRepository.save(AdminAuditLog.create(
                    admin,
                    "SYSTEM_CONFIG_UPDATE",
                    ReportTargetType.USER,
                    admin.getId(),
                    null,
                    String.format("시스템 설정 변경 [%s: %s -> %s]", configKey, oldValue, newValue.trim())));
    
            return SystemConfigResponse.from(saved);
        }

    /**
         * 다건 시스템 설정값 일괄 수정 (프론트엔드 일괄 저장 화면 대응)
         */
        @Transactional
        public List<SystemConfigResponse> updateMultipleConfigs(UUID adminId, Map<String, String> configs) {
            User admin = userRepository.findById(adminId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "어드민 유저 정보를 찾을 수 없습니다."));

            // [사전 검증 1] 값 유효성 검증 (하나라도 오류 시 전체 롤백)
            for (Map.Entry<String, String> entry : configs.entrySet()) {
                validateConfigValue(entry.getKey(), entry.getValue());
            }

            // [사전 검증 2] 키 존재 여부 일괄 확인 (쿼리 1번) — 없는 키가 있으면 즉시 404
            Map<String, SystemConfig> configMap = systemConfigRepository
                    .findByConfigKeyIn(configs.keySet())
                    .stream()
                    .collect(java.util.stream.Collectors.toMap(SystemConfig::getConfigKey, c -> c));

            List<String> missingKeys = configs.keySet().stream()
                    .filter(key -> !configMap.containsKey(key))
                    .toList();
            if (!missingKeys.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "존재하지 않는 설정 키입니다: " + missingKeys);
            }

            // 검증 통과 후 일괄 수정 (조회 결과 재사용, 추가 쿼리 없음)
            for (Map.Entry<String, String> entry : configs.entrySet()) {
                String key = entry.getKey();
                String newValue = entry.getValue().trim();
                SystemConfig config = configMap.get(key);

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
            }

            return getAllConfigs();
        }

    /**
         * [신규 추가] 시스템 설정 키별 타입 및 범위 검증
         */
        private void validateConfigValue(String configKey, String newValue) {
            if (newValue == null || newValue.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "설정값은 비어 있을 수 없습니다.");
            }
    
            String value = newValue.trim();
            switch (configKey) {
                case "AUTO_HIDE_THRESHOLD" -> {
                    try {
                        long val = Long.parseLong(value);
                        if (val < 1) {
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "자동 숨김 임계값은 1 이상의 정수여야 합니다.");
                        }
                    } catch (NumberFormatException e) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "자동 숨김 임계값은 올바른 숫자여야 합니다: " + value);
                    }
                }
                case "AUTO_HIDE_ENABLED", "NEW_REPORT_ALERT" -> {
                    if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, configKey + " 설정값은 true 또는 false여야 합니다.");
                    }
                }
                case "RE_REVIEW_DEADLINE_DAYS" -> {
                    try {
                        long days = Long.parseLong(value);
                        if (days < 1) {
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "신고 재검토 기한은 1일 이상이어야 합니다.");
                        }
                    } catch (NumberFormatException e) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "재검토 기한은 올바른 숫자여야 합니다: " + value);
                    }
                }
                case "DEFAULT_SANCTION_LEVEL" -> {
                    try {
                        PenaltyType.valueOf(value);
                    } catch (IllegalArgumentException e) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "유효하지 않은 기본 제재 수준입니다: " + value);
                    }
                }
            }
        }
}