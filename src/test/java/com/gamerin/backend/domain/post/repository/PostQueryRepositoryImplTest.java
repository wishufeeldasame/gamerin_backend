package com.gamerin.backend.domain.post.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

@ExtendWith(MockitoExtension.class)
class PostQueryRepositoryImplTest {

    @Mock
    private EntityManager entityManager;

    @Mock
    private PostRepository postRepository;

    @Mock
    private PostMediaRepository postMediaRepository;

    @Mock
    private PostBookmarkRepository postBookmarkRepository;

    @Mock
    private Query query;

    private PostQueryRepositoryImpl postQueryRepository;

    @BeforeEach
    void setUp() {
        postQueryRepository = new PostQueryRepositoryImpl(
                entityManager,
                postRepository,
                postMediaRepository,
                postBookmarkRepository
        );
    }

    @Test
    void findFeedPostsFiltersDeletedAuthors() {
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());

        List<?> posts = postQueryRepository.findFeedPosts(UUID.randomUUID(), false, null, 20);

        assertThat(posts).isEmpty();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue())
                .contains("join users u on u.id = p.author_id")
                .contains("where p.deleted_at is null")
                .contains("and u.deleted_at is null");
    }
}
