package com.gamerin.backend.domain.follow.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gamerin.backend.domain.follow.entity.Follow;

public interface FollowRepository extends JpaRepository<Follow, UUID> {

    boolean existsByFollowerIdAndFolloweeId(UUID followerId, UUID followeeId);

    Optional<Follow> findByFollowerIdAndFolloweeId(UUID followerId, UUID followeeId);

    long countByFollowerId(UUID followerId);

    long countByFolloweeId(UUID followeeId);
}
