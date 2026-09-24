package com.gamerin.backend.domain.mention.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gamerin.backend.domain.mention.entity.UserMention;

public interface UserMentionRepository extends JpaRepository<UserMention, UUID> {

    long countByPostIdAndMentionedUserId(UUID postId, UUID mentionedUserId);

    long countByCommentIdAndMentionedUserId(UUID commentId, UUID mentionedUserId);
}
