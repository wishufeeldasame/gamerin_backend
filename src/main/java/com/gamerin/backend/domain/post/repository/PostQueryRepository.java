package com.gamerin.backend.domain.post.repository;

import java.util.List;
import java.util.UUID;

import com.gamerin.backend.domain.post.dto.response.TrendingGameResponse;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostMedia;

public interface PostQueryRepository {

    List<Post> findFeedPosts(UUID viewerId, boolean followingOnly, String cursor, int limit);

    List<Post> findUserPosts(String handle, String cursor, int limit);

    List<PostMedia> findUserMedia(String handle, String cursor, int limit);

    List<TrendingGameResponse> findTrendingGames(int days, int limit);
}
