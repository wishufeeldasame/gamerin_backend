package com.gamerin.backend.domain.bookmark.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.bookmark.entity.BookmarkCollection;
import com.gamerin.backend.domain.bookmark.entity.BookmarkCollectionItem;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostBookmark;
import com.gamerin.backend.domain.post.entity.PostMedia;
import com.gamerin.backend.domain.post.entity.PostMediaType;
import com.gamerin.backend.domain.post.repository.PostBookmarkRepository;
import com.gamerin.backend.domain.post.repository.PostMediaRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;

import jakarta.persistence.EntityManager;

@DataJpaTest
@Import(BookmarkCollectionQueryRepository.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BookmarkCollectionRepositoryTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PostRepository postRepository;
    @Autowired
    private PostBookmarkRepository postBookmarkRepository;
    @Autowired
    private PostMediaRepository postMediaRepository;
    @Autowired
    private BookmarkCollectionRepository collectionRepository;
    @Autowired
    private BookmarkCollectionItemRepository itemRepository;
    @Autowired
    private BookmarkCollectionQueryRepository queryRepository;
    @Autowired
    private EntityManager entityManager;

    @Test
    void oneBookmarkCanBelongToMultipleCollectionsButNotTwiceToSameCollection() {
        User user = saveUser("multi");
        PostBookmark bookmark = saveBookmark(user, "post");
        BookmarkCollection first = saveCollection(user, "First", "first");
        BookmarkCollection second = saveCollection(user, "Second", "second");

        itemRepository.saveAndFlush(BookmarkCollectionItem.create(first, bookmark));
        itemRepository.saveAndFlush(BookmarkCollectionItem.create(second, bookmark));

        assertThat(itemRepository.findCollectionIdsContainingPost(user.getId(), bookmark.getPost().getId()))
                .containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThatThrownBy(() -> itemRepository.saveAndFlush(
                BookmarkCollectionItem.create(first, bookmark)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void metricsAndContainsPostExcludeSoftDeletedPosts() {
        User user = saveUser("metrics");
        PostBookmark bookmark = saveBookmark(user, "visible");
        BookmarkCollection collection = saveCollection(user, "Clips", "clips");
        itemRepository.saveAndFlush(BookmarkCollectionItem.create(collection, bookmark));
        entityManager.clear();

        var visible = queryRepository.findCollectionMetrics(user.getId(), bookmark.getPost().getId())
                .get(collection.getId());
        assertThat(visible.bookmarkCount()).isEqualTo(1);
        assertThat(visible.containsPost()).isTrue();

        Post managedPost = postRepository.findById(bookmark.getPost().getId()).orElseThrow();
        managedPost.softDelete();
        postRepository.flush();
        entityManager.clear();

        var deleted = queryRepository.findCollectionMetrics(user.getId(), bookmark.getPost().getId())
                .get(collection.getId());
        assertThat(deleted.bookmarkCount()).isZero();
        assertThat(deleted.containsPost()).isFalse();
        assertThat(queryRepository.findCollectionItems(
                user.getId(),
                collection.getId(),
                null,
                20,
                null,
                false
        )).isEmpty();
    }

    @Test
    void unclassifiedQueryUsesLiteralSearchAndCreatedAtCursor() {
        User user = saveUser("search");
        PostBookmark expected = saveBookmark(user, "win rate 100%");
        saveBookmark(user, "win rate 100x");
        BookmarkCollection collection = saveCollection(user, "Classified", "classified");
        PostBookmark classified = saveBookmark(user, "win rate 100% classified");
        itemRepository.saveAndFlush(BookmarkCollectionItem.create(collection, classified));
        entityManager.clear();

        List<PostBookmark> result = queryRepository.findUnclassifiedBookmarks(
                user.getId(),
                null,
                20,
                "100%",
                false
        );

        assertThat(result).extracting(PostBookmark::getId).containsExactly(expected.getId());
        String cursor = result.getFirst().getCreatedAt() + "|" + result.getFirst().getId();
        assertThat(queryRepository.findUnclassifiedBookmarks(
                user.getId(),
                cursor,
                20,
                "100%",
                false
        )).isEmpty();
    }

    @Test
    void collectionAndUnclassifiedQueriesRejectInvalidCursors() {
        User user = saveUser("cursor");
        BookmarkCollection collection = saveCollection(user, "Cursor", "cursor");

        assertThatThrownBy(() -> queryRepository.findCollectionItems(
                user.getId(),
                collection.getId(),
                "invalid",
                20,
                null,
                false
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThatThrownBy(() -> queryRepository.findUnclassifiedBookmarks(
                user.getId(),
                "invalid",
                20,
                null,
                false
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void coverUsesImageUrlAndVideoThumbnail() {
        User user = saveUser("cover");
        PostBookmark imageBookmark = saveBookmark(user, "image");
        PostBookmark videoBookmark = saveBookmark(user, "video");
        postMediaRepository.saveAndFlush(PostMedia.create(
                imageBookmark.getPost(),
                PostMediaType.IMAGE,
                "https://cdn.example/image.jpg",
                null,
                0
        ));
        postMediaRepository.saveAndFlush(PostMedia.create(
                videoBookmark.getPost(),
                PostMediaType.VIDEO,
                "https://cdn.example/video.mp4",
                "https://cdn.example/video-cover.jpg",
                0
        ));
        BookmarkCollection imageCollection = saveCollection(user, "Images", "images");
        BookmarkCollection videoCollection = saveCollection(user, "Videos", "videos");
        itemRepository.saveAndFlush(BookmarkCollectionItem.create(imageCollection, imageBookmark));
        itemRepository.saveAndFlush(BookmarkCollectionItem.create(videoCollection, videoBookmark));
        entityManager.clear();

        var metrics = queryRepository.findCollectionMetrics(user.getId(), null);

        assertThat(metrics.get(imageCollection.getId()).coverImageUrl())
                .isEqualTo("https://cdn.example/image.jpg");
        assertThat(metrics.get(videoCollection.getId()).coverImageUrl())
                .isEqualTo("https://cdn.example/video-cover.jpg");
    }

    private User saveUser(String handle) {
        return userRepository.saveAndFlush(User.createLocal(
                handle + "@example.com",
                handle,
                handle,
                "encoded-password"
        ));
    }

    private PostBookmark saveBookmark(User user, String content) {
        Post post = postRepository.saveAndFlush(Post.create(user, content));
        return postBookmarkRepository.saveAndFlush(PostBookmark.create(post, user));
    }

    private BookmarkCollection saveCollection(
            User user,
            String name,
            String normalizedName
    ) {
        return collectionRepository.saveAndFlush(
                BookmarkCollection.create(user, name, normalizedName)
        );
    }
}
