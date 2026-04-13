package com.gamerin.backend.domain.user.repository;

import com.gamerin.backend.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;


public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByHandle(String handle);
    boolean existsByHandle(String handle);

    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    
}
