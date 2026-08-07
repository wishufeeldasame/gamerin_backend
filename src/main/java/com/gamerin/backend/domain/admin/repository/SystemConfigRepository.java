package com.gamerin.backend.domain.admin.repository;

import com.gamerin.backend.domain.admin.entity.SystemConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// 시스템 설정(자동 숨김 임계값, 알림 설정 등) 조회 및 수정.
public interface SystemConfigRepository extends JpaRepository<SystemConfig, String> {

    Optional<SystemConfig> findByConfigKey(String configKey);
}