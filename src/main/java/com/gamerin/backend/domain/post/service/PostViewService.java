package com.gamerin.backend.domain.post.service;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.post.repository.PostViewCommandRepository;
import com.gamerin.backend.domain.user.repository.UserRepository;

/**
 * 게시물 조회수(#183). 상세 진입과 피드 카드 노출을 같은 규칙으로 센다:
 * 같은 사용자(본인 포함)가 같은 글을 24시간 안에 몇 번 보든 1회. 삭제·숨김(soft delete)된 글은 세지 않는다.
 * 같은 요청 안에서 원자적 UPDATE로 증가시킨다(비동기 큐 없음).
 */
@Service
public class PostViewService {

    static final Duration DEDUP_WINDOW = Duration.ofHours(24);

    private final PostRepository postRepository;
    private final PostViewCommandRepository postViewRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    @Autowired
    public PostViewService(PostRepository postRepository, PostViewCommandRepository postViewRepository,
            UserRepository userRepository) {
        this(postRepository, postViewRepository, userRepository, Clock.systemUTC());
    }

    PostViewService(PostRepository postRepository, PostViewCommandRepository postViewRepository,
            UserRepository userRepository, Clock clock) {
        this.postRepository = postRepository;
        this.postViewRepository = postViewRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    /** 실제로 증가한 게시물 수를 돌려준다. */
    @Transactional
    public int record(UUID userId, Collection<UUID> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return 0;
        }
        // like()처럼 사용자 → 게시물 순으로 잠가야 한다. post_views 삽입의 FK 검사가 게시물을 먼저 잡으면 좋아요와 교착할 수 있다.
        if (userRepository.findActiveByIdForUpdate(userId).isEmpty()) {
            return 0;
        }
        // 글 행을 먼저 잠근 뒤 활성 여부를 확인하므로, 판정과 쓰기 사이에 숨김이 끼어들어 24시간 중복만 남는 일이 없다.
        List<UUID> activeIds = postRepository.lockActiveIn(postIds.stream().collect(Collectors.toSet()))
                .stream().map(Post::getId).toList();
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime cutoff = now.minus(DEDUP_WINDOW);
        int counted = 0;
        for (UUID postId : activeIds) {
            boolean isNewView = postViewRepository.refreshIfExpired(postId, userId, now, cutoff) > 0
                    || postViewRepository.insertIfAbsent(postId, userId, now) > 0;
            if (isNewView && postViewRepository.increaseViewCount(postId) > 0) {
                counted++;
            }
        }
        return counted;
    }
}
