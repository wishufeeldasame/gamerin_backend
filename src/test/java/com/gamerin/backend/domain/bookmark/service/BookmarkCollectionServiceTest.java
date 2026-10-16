package com.gamerin.backend.domain.bookmark.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.bookmark.dto.request.CreateBookmarkCollectionRequest;
import com.gamerin.backend.domain.bookmark.dto.request.RenameBookmarkCollectionRequest;
import com.gamerin.backend.domain.bookmark.entity.BookmarkCollection;
import com.gamerin.backend.domain.bookmark.entity.BookmarkCollectionItem;
import com.gamerin.backend.domain.bookmark.repository.BookmarkCollectionItemRepository;
import com.gamerin.backend.domain.bookmark.repository.BookmarkCollectionQueryRepository;
import com.gamerin.backend.domain.bookmark.repository.BookmarkCollectionQueryRepository.CollectionMetrics;
import com.gamerin.backend.domain.bookmark.repository.BookmarkCollectionRepository;
import com.gamerin.backend.domain.post.dto.response.PostCardResponse;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostBookmark;
import com.gamerin.backend.domain.post.repository.PostBookmarkRepository;
import com.gamerin.backend.domain.post.service.PostBookmarkCommandService;
import com.gamerin.backend.domain.post.service.PostResponseAssembler;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@ExtendWith(MockitoExtension.class)
class BookmarkCollectionServiceTest {

    @Mock
    private BookmarkCollectionRepository collectionRepository;
    @Mock
    private BookmarkCollectionItemRepository itemRepository;
    @Mock
    private BookmarkCollectionQueryRepository queryRepository;
    @Mock
    private PostBookmarkRepository postBookmarkRepository;
    @Mock
    private PostBookmarkCommandService postBookmarkCommandService;
    @Mock
    private PostResponseAssembler postResponseAssembler;

    private BookmarkCollectionService service;

    @BeforeEach
    void setUp() {
        service = new BookmarkCollectionService(
                collectionRepository,
                itemRepository,
                queryRepository,
                postBookmarkRepository,
                postBookmarkCommandService,
                postResponseAssembler
        );
    }

    @Test
    void createTrimsNameAndAllowsEmptyCollection() {
        User user = user();
        CustomUserPrincipal principal = CustomUserPrincipal.from(user);
        stubCollectionCreation(user);

        var response = service.create(principal, new CreateBookmarkCollectionRequest("  Clips  ", null));

        ArgumentCaptor<BookmarkCollection> captor = ArgumentCaptor.forClass(BookmarkCollection.class);
        verify(collectionRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Clips");
        assertThat(captor.getValue().getNormalizedName()).isEqualTo("clips");
        assertThat(response.name()).isEqualTo("Clips");
        assertThat(response.bookmarkCount()).isZero();
        verify(postBookmarkCommandService, never()).lockAccessiblePost(any());
    }

    @Test
    void createWithInitialPostCreatesCanonicalBookmarkAndMembershipAtomically() {
        User user = user();
        Post post = post(user);
        PostBookmark bookmark = bookmark(post, user);
        CustomUserPrincipal principal = CustomUserPrincipal.from(user);
        UUID collectionId = stubCollectionCreation(user);
        when(postBookmarkCommandService.lockAccessiblePost(post.getId())).thenReturn(post);
        when(postBookmarkCommandService.getOrCreate(user, post)).thenReturn(bookmark);
        when(itemRepository.existsByCollectionIdAndPostBookmarkId(collectionId, bookmark.getId()))
                .thenReturn(false);
        when(queryRepository.findCollectionMetrics(user.getId(), post.getId())).thenReturn(Map.of(
                collectionId,
                new CollectionMetrics(1, null, true)
        ));

        var response = service.create(
                principal,
                new CreateBookmarkCollectionRequest("Ranked", post.getId())
        );

        verify(itemRepository).save(any(BookmarkCollectionItem.class));
        verify(itemRepository).flush();
        assertThat(response.containsPost()).isTrue();
        assertThat(response.bookmarkCount()).isEqualTo(1);
    }

    @Test
    void createRejectsCaseInsensitiveDuplicateName() {
        User user = user();
        when(postBookmarkCommandService.lockCurrentUser(any())).thenReturn(user);
        when(collectionRepository.countByUserId(user.getId())).thenReturn(1L);
        when(collectionRepository.existsByUserIdAndNormalizedName(user.getId(), "clips"))
                .thenReturn(true);

        assertStatus(
                () -> service.create(
                        CustomUserPrincipal.from(user),
                        new CreateBookmarkCollectionRequest("CLIPS", null)
                ),
                HttpStatus.CONFLICT
        );
        verify(collectionRepository, never()).saveAndFlush(any());
    }

    @Test
    void createRejectsCollectionLimit() {
        User user = user();
        when(postBookmarkCommandService.lockCurrentUser(any())).thenReturn(user);
        when(collectionRepository.countByUserId(user.getId())).thenReturn(100L);

        assertStatus(
                () -> service.create(
                        CustomUserPrincipal.from(user),
                        new CreateBookmarkCollectionRequest("One more", null)
                ),
                HttpStatus.CONFLICT
        );
    }

    @Test
    void anotherUsersCollectionIsHiddenAsNotFound() {
        User user = user();
        UUID collectionId = UUID.randomUUID();
        when(postBookmarkCommandService.lockCurrentUser(any())).thenReturn(user);
        when(collectionRepository.findByIdAndUserId(collectionId, user.getId()))
                .thenReturn(Optional.empty());

        assertStatus(
                () -> service.rename(
                        CustomUserPrincipal.from(user),
                        collectionId,
                        new RenameBookmarkCollectionRequest("Private")
                ),
                HttpStatus.NOT_FOUND
        );
    }

    @Test
    void repeatedAddIsIdempotentAndReturnsAuthoritativeMembership() {
        User user = user();
        Post post = post(user);
        PostBookmark bookmark = bookmark(post, user);
        BookmarkCollection collection = collection(user, "Clips");
        when(postBookmarkCommandService.lockCurrentUser(any())).thenReturn(user);
        when(collectionRepository.findByIdAndUserId(collection.getId(), user.getId()))
                .thenReturn(Optional.of(collection));
        when(postBookmarkCommandService.lockAccessiblePost(post.getId())).thenReturn(post);
        when(postBookmarkCommandService.getOrCreate(user, post)).thenReturn(bookmark);
        when(itemRepository.existsByCollectionIdAndPostBookmarkId(collection.getId(), bookmark.getId()))
                .thenReturn(true);
        when(postBookmarkRepository.existsByPostIdAndUserId(post.getId(), user.getId())).thenReturn(true);
        when(itemRepository.findCollectionIdsContainingPost(user.getId(), post.getId()))
                .thenReturn(List.of(collection.getId()));

        var response = service.addPost(CustomUserPrincipal.from(user), collection.getId(), post.getId());

        verify(itemRepository, never()).save(any());
        assertThat(response.bookmarkedByMe()).isTrue();
        assertThat(response.collectionIds()).containsExactly(collection.getId());
    }

    @Test
    void removingLastMembershipPreservesCanonicalBookmark() {
        User user = user();
        Post post = post(user);
        PostBookmark bookmark = bookmark(post, user);
        BookmarkCollection collection = collection(user, "Clips");
        BookmarkCollectionItem item = item(collection, bookmark);
        when(postBookmarkCommandService.lockCurrentUser(any())).thenReturn(user);
        when(collectionRepository.findByIdAndUserId(collection.getId(), user.getId()))
                .thenReturn(Optional.of(collection));
        when(postBookmarkCommandService.lockAccessiblePost(post.getId())).thenReturn(post);
        when(postBookmarkRepository.findByPostIdAndUserId(post.getId(), user.getId()))
                .thenReturn(Optional.of(bookmark));
        when(itemRepository.findByCollectionIdAndPostBookmarkId(collection.getId(), bookmark.getId()))
                .thenReturn(Optional.of(item));
        when(postBookmarkRepository.existsByPostIdAndUserId(post.getId(), user.getId())).thenReturn(true);
        when(itemRepository.findCollectionIdsContainingPost(user.getId(), post.getId()))
                .thenReturn(List.of());

        var response = service.removePost(CustomUserPrincipal.from(user), collection.getId(), post.getId());

        verify(itemRepository).delete(item);
        verify(postBookmarkRepository, never()).delete(any());
        assertThat(response.bookmarkedByMe()).isTrue();
        assertThat(response.collectionIds()).isEmpty();
    }

    @Test
    void deletingCollectionPreservesCanonicalBookmarks() {
        User user = user();
        BookmarkCollection collection = collection(user, "Clips");
        when(postBookmarkCommandService.lockCurrentUser(any())).thenReturn(user);
        when(collectionRepository.findByIdAndUserId(collection.getId(), user.getId()))
                .thenReturn(Optional.of(collection));

        service.delete(CustomUserPrincipal.from(user), collection.getId());

        verify(itemRepository).deleteAllByCollectionId(collection.getId());
        verify(collectionRepository).delete(collection);
        verify(postBookmarkRepository, never()).delete(any());
    }

    @Test
    void collectionBookmarksUseAddedAtCursorAndRequestedPageSize() {
        User user = user();
        BookmarkCollection collection = collection(user, "Clips");
        Post firstPost = post(user);
        Post secondPost = post(user);
        BookmarkCollectionItem first = item(collection, bookmark(firstPost, user));
        BookmarkCollectionItem second = item(collection, bookmark(secondPost, user));
        when(postBookmarkCommandService.getCurrentUser(any())).thenReturn(user);
        when(collectionRepository.findByIdAndUserId(collection.getId(), user.getId()))
                .thenReturn(Optional.of(collection));
        when(queryRepository.findCollectionItems(
                user.getId(),
                collection.getId(),
                null,
                2,
                "clip",
                true
        )).thenReturn(List.of(first, second));
        when(postResponseAssembler.toPostCards(eq(List.of(firstPost)), eq(user.getId())))
                .thenReturn(List.of());

        var page = service.getCollectionBookmarks(
                CustomUserPrincipal.from(user),
                collection.getId(),
                null,
                1,
                "clip",
                true
        );

        assertThat(page.hasNext()).isTrue();
        assertThat(page.nextCursor()).isEqualTo(first.getAddedAt() + "|" + first.getId());
    }

    private UUID stubCollectionCreation(User user) {
        UUID collectionId = UUID.randomUUID();
        when(postBookmarkCommandService.lockCurrentUser(any())).thenReturn(user);
        when(collectionRepository.countByUserId(user.getId())).thenReturn(0L);
        when(collectionRepository.existsByUserIdAndNormalizedName(eq(user.getId()), any()))
                .thenReturn(false);
        when(collectionRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            BookmarkCollection collection = invocation.getArgument(0);
            ReflectionTestUtils.setField(collection, "id", collectionId);
            ReflectionTestUtils.setField(collection, "createdAt", OffsetDateTime.now());
            ReflectionTestUtils.setField(collection, "updatedAt", OffsetDateTime.now());
            return collection;
        });
        return collectionId;
    }

    private User user() {
        User user = User.createLocal("collection@example.com", "collector", "Collector", "hash");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private Post post(User author) {
        Post post = Post.create(author, "bookmark collection post");
        ReflectionTestUtils.setField(post, "id", UUID.randomUUID());
        return post;
    }

    private PostBookmark bookmark(Post post, User user) {
        PostBookmark bookmark = PostBookmark.create(post, user);
        ReflectionTestUtils.setField(bookmark, "id", UUID.randomUUID());
        return bookmark;
    }

    private BookmarkCollection collection(User user, String name) {
        BookmarkCollection collection = BookmarkCollection.create(user, name, name.toLowerCase());
        ReflectionTestUtils.setField(collection, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(collection, "createdAt", OffsetDateTime.now());
        ReflectionTestUtils.setField(collection, "updatedAt", OffsetDateTime.now());
        return collection;
    }

    private BookmarkCollectionItem item(
            BookmarkCollection collection,
            PostBookmark bookmark
    ) {
        BookmarkCollectionItem item = BookmarkCollectionItem.create(collection, bookmark);
        ReflectionTestUtils.setField(item, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(item, "addedAt", OffsetDateTime.now());
        return item;
    }

    private void assertStatus(Runnable operation, HttpStatus status) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(status);
    }
}
