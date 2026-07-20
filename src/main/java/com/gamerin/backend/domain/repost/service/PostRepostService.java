package com.gamerin.backend.domain.repost.service;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.repost.dto.response.RepostActionResponse;
import com.gamerin.backend.domain.repost.entity.PostRepost;
import com.gamerin.backend.domain.repost.repository.PostRepostRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@Service
public class PostRepostService {

    private final UserRepository userRepository;
    private final PostRepository postRepository;
    private final PostRepostRepository postRepostRepository;

    public PostRepostService(
            UserRepository userRepository,
            PostRepository postRepository,
            PostRepostRepository postRepostRepository
    ) {
        this.userRepository = userRepository;
        this.postRepository = postRepository;
        this.postRepostRepository = postRepostRepository;
    }

    @Transactional
    public RepostActionResponse repost(CustomUserPrincipal principal, UUID postId) {
        User user = lockCurrentUser(principal);
        Post post = getAccessiblePost(postId);

        if (post.getAuthor().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot repost your own post.");
        }

        PostRepost repost = postRepostRepository.findByPostIdAndUserId(postId, user.getId())
                .orElseGet(() -> postRepostRepository.saveAndFlush(PostRepost.create(post, user)));

        return new RepostActionResponse(
                postId,
                true,
                postRepostRepository.countActiveByPostId(postId),
                repost.getRepostedAt()
        );
    }

    @Transactional
    public RepostActionResponse unrepost(CustomUserPrincipal principal, UUID postId) {
        User user = lockCurrentUser(principal);
        getAccessiblePost(postId);

        postRepostRepository.findByPostIdAndUserId(postId, user.getId())
                .ifPresent(postRepostRepository::delete);
        postRepostRepository.flush();

        return new RepostActionResponse(
                postId,
                false,
                postRepostRepository.countActiveByPostId(postId),
                null
        );
    }

    private User lockCurrentUser(CustomUserPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }

        return userRepository.findActiveByIdForUpdate(principal.getUserId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Authenticated user not found."
                ));
    }

    private Post getAccessiblePost(UUID postId) {
        return postRepository.findAccessibleById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found."));
    }
}
