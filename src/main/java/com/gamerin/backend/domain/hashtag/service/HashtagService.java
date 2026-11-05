package com.gamerin.backend.domain.hashtag.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gamerin.backend.domain.hashtag.model.ParsedHashtag;
import com.gamerin.backend.domain.hashtag.repository.HashtagCommandRepository;
import com.gamerin.backend.domain.post.entity.Post;

@Service
@Transactional
public class HashtagService {

    private final HashtagParser hashtagParser;
    private final HashtagCommandRepository hashtagCommandRepository;

    public HashtagService(
            HashtagParser hashtagParser,
            HashtagCommandRepository hashtagCommandRepository) {
        this.hashtagParser = hashtagParser;
        this.hashtagCommandRepository = hashtagCommandRepository;
    }

    public void attachToPost(Post post) {
        if (post == null || post.getId() == null) {
            throw new IllegalArgumentException("A saved post is required for hashtag attachment.");
        }

        for (ParsedHashtag parsed : hashtagParser.parse(post.getContent())) {
            UUID hashtagId = hashtagCommandRepository.findOrCreate(
                    parsed.displayName(),
                    parsed.normalizedName());
            hashtagCommandRepository.attachToPost(post.getId(), hashtagId);
        }
    }

    /**
     * KP-C3 (#92): 게시물 수정 시 기존 해시태그 매핑을 제거하고 최신 본문 기준으로 해시태그를 재파싱·연결한다.
     */
    public void syncForPost(Post post) {
        if (post == null || post.getId() == null) {
            throw new IllegalArgumentException("A saved post is required for hashtag synchronization.");
        }

        hashtagCommandRepository.deleteByPostId(post.getId());
        attachToPost(post);
    }
}