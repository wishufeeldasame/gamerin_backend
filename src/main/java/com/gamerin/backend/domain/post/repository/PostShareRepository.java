package com.gamerin.backend.domain.post.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gamerin.backend.domain.post.entity.PostShare;

public interface PostShareRepository extends JpaRepository<PostShare, UUID> {
}
