package com.gamerin.backend.domain.hashtag.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gamerin.backend.domain.hashtag.entity.Hashtag;

public interface HashtagRepository extends JpaRepository<Hashtag, UUID> {

    Optional<Hashtag> findByNormalizedName(String normalizedName);
}
