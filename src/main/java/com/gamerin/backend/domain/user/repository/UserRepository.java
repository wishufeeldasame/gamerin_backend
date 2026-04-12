package com.gamerin.backend.domain.user.repository;

import com.gamerin.backend.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByHandle(String handle);
    boolean existsByHandle(String handle);
    Optional<User> findByProviderAndProviderId(String provider, String providerId);
}
