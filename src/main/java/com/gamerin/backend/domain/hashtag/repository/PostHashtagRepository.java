package com.gamerin.backend.domain.hashtag.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gamerin.backend.domain.hashtag.entity.PostHashtag;

public interface PostHashtagRepository extends JpaRepository<PostHashtag, UUID> {

    long countByHashtagId(UUID hashtagId);

    boolean existsByPostIdAndHashtagId(UUID postId, UUID hashtagId);
}
