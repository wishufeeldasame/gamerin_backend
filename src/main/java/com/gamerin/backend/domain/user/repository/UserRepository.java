package com.gamerin.backend.domain.user.repository;

<<<<<<< HEAD
import com.gamerin.backend.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByHandle(String handle);
    boolean existsByHandle(String handle);
    Optional<User> findByProviderAndProviderId(String provider, String providerId);
=======
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.gamerin.backend.domain.user.entity.User;

public interface UserRepository extends JpaRepository<User, UUID> {
    boolean existsByHandle(String handle);
    java.util.Optional<User> findByHandle(String handle);
>>>>>>> main
}
