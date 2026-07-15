package com.gamerin.backend.domain.bookmark.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.bookmark.dto.request.CreateBookmarkCollectionRequest;
import com.gamerin.backend.domain.bookmark.dto.request.RenameBookmarkCollectionRequest;
import com.gamerin.backend.domain.bookmark.dto.response.BookmarkCollectionResponse;
import com.gamerin.backend.domain.bookmark.dto.response.BookmarkMembershipResponse;
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
import com.gamerin.backend.global.response.CursorPageResponse;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@Service
public class BookmarkCollectionService {

    private static final int MAX_COLLECTIONS_PER_USER = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final BookmarkCollectionRepository collectionRepository;
    private final BookmarkCollectionItemRepository itemRepository;
    private final BookmarkCollectionQueryRepository queryRepository;
    private final PostBookmarkRepository postBookmarkRepository;
    private final PostBookmarkCommandService postBookmarkCommandService;
    private final PostResponseAssembler postResponseAssembler;

    public BookmarkCollectionService(
            BookmarkCollectionRepository collectionRepository,
            BookmarkCollectionItemRepository itemRepository,
            BookmarkCollectionQueryRepository queryRepository,
            PostBookmarkRepository postBookmarkRepository,
            PostBookmarkCommandService postBookmarkCommandService,
            PostResponseAssembler postResponseAssembler
    ) {
        this.collectionRepository = collectionRepository;
        this.itemRepository = itemRepository;
        this.queryRepository = queryRepository;
        this.postBookmarkRepository = postBookmarkRepository;
        this.postBookmarkCommandService = postBookmarkCommandService;
        this.postResponseAssembler = postResponseAssembler;
    }

    @Transactional
    public BookmarkCollectionResponse create(
            CustomUserPrincipal principal,
            CreateBookmarkCollectionRequest request
    ) {
        User user = postBookmarkCommandService.lockCurrentUser(principal);
        if (collectionRepository.countByUserId(user.getId()) >= MAX_COLLECTIONS_PER_USER) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A user can create up to 100 bookmark collections."
            );
        }

        BookmarkCollectionName name = BookmarkCollectionName.from(request.name());
        ensureNameAvailable(user.getId(), name.normalizedName(), null);

        BookmarkCollection collection;
        try {
            collection = collectionRepository.saveAndFlush(
                    BookmarkCollection.create(user, name.displayName(), name.normalizedName())
            );
        } catch (DataIntegrityViolationException ex) {
            throw duplicateName();
        }

        if (request.initialPostId() != null) {
            addPostInternal(user, collection, request.initialPostId());
            itemRepository.flush();
        }

        return toResponse(collection, metricFor(user.getId(), collection.getId(), request.initialPostId()));
    }

    @Transactional(readOnly = true)
    public List<BookmarkCollectionResponse> getCollections(
            CustomUserPrincipal principal,
            UUID postId
    ) {
        UUID userId = getCurrentUserId(principal);
        if (postId != null) {
            postBookmarkCommandService.getAccessiblePost(postId);
        }

        List<BookmarkCollection> collections = collectionRepository
                .findAllByUserIdOrderByCreatedAtDescIdDesc(userId);
        Map<UUID, CollectionMetrics> metrics = queryRepository.findCollectionMetrics(userId, postId);
        return collections.stream()
                .map(collection -> toResponse(
                        collection,
                        metrics.getOrDefault(collection.getId(), CollectionMetrics.empty())
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public BookmarkCollectionResponse getCollection(
            CustomUserPrincipal principal,
            UUID collectionId
    ) {
        UUID userId = getCurrentUserId(principal);
        BookmarkCollection collection = getOwnedCollection(collectionId, userId);
        return toResponse(collection, metricFor(userId, collectionId, null));
    }

    @Transactional
    public BookmarkCollectionResponse rename(
            CustomUserPrincipal principal,
            UUID collectionId,
            RenameBookmarkCollectionRequest request
    ) {
        User user = postBookmarkCommandService.lockCurrentUser(principal);
        BookmarkCollection collection = getOwnedCollection(collectionId, user.getId());
        BookmarkCollectionName name = BookmarkCollectionName.from(request.name());
        ensureNameAvailable(user.getId(), name.normalizedName(), collectionId);

        collection.rename(name.displayName(), name.normalizedName());
        try {
            collectionRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw duplicateName();
        }
        return toResponse(collection, metricFor(user.getId(), collectionId, null));
    }

    @Transactional
    public void delete(CustomUserPrincipal principal, UUID collectionId) {
        User user = postBookmarkCommandService.lockCurrentUser(principal);
        BookmarkCollection collection = getOwnedCollection(collectionId, user.getId());
        itemRepository.deleteAllByCollectionId(collectionId);
        collectionRepository.delete(collection);
    }

    @Transactional
    public BookmarkMembershipResponse addPost(
            CustomUserPrincipal principal,
            UUID collectionId,
            UUID postId
    ) {
        User user = postBookmarkCommandService.lockCurrentUser(principal);
        BookmarkCollection collection = getOwnedCollection(collectionId, user.getId());
        addPostInternal(user, collection, postId);
        itemRepository.flush();
        return membershipResponse(user.getId(), postId, collection);
    }

    @Transactional
    public BookmarkMembershipResponse removePost(
            CustomUserPrincipal principal,
            UUID collectionId,
        UUID postId
    ) {
        User user = postBookmarkCommandService.lockCurrentUser(principal);
        BookmarkCollection collection = getOwnedCollection(collectionId, user.getId());
        postBookmarkCommandService.lockAccessiblePost(postId);

        postBookmarkRepository.findByPostIdAndUserId(postId, user.getId())
                .flatMap(bookmark -> itemRepository.findByCollectionIdAndPostBookmarkId(
                        collectionId,
                        bookmark.getId()
                ))
                .ifPresent(itemRepository::delete);
        itemRepository.flush();
        return membershipResponse(user.getId(), postId, collection);
    }

    @Transactional(readOnly = true)
    public CursorPageResponse<PostCardResponse> getCollectionBookmarks(
            CustomUserPrincipal principal,
            UUID collectionId,
            String cursor,
            int size,
            String keyword,
            boolean mediaOnly
    ) {
        UUID userId = getCurrentUserId(principal);
        getOwnedCollection(collectionId, userId);
        int pageSize = clampSize(size);
        List<BookmarkCollectionItem> loadedItems = queryRepository.findCollectionItems(
                userId,
                collectionId,
                cursor,
                pageSize + 1,
                keyword,
                mediaOnly
        );
        boolean hasNext = loadedItems.size() > pageSize;
        List<BookmarkCollectionItem> pageItems = hasNext
                ? loadedItems.subList(0, pageSize)
                : loadedItems;
        List<Post> posts = pageItems.stream()
                .map(item -> item.getPostBookmark().getPost())
                .toList();

        return new CursorPageResponse<>(
                postResponseAssembler.toPostCards(posts, userId),
                buildItemCursor(pageItems, hasNext),
                hasNext
        );
    }

    private void addPostInternal(User user, BookmarkCollection collection, UUID postId) {
        Post post = postBookmarkCommandService.lockAccessiblePost(postId);
        PostBookmark bookmark = postBookmarkCommandService.getOrCreate(user, post);
        if (itemRepository.existsByCollectionIdAndPostBookmarkId(collection.getId(), bookmark.getId())) {
            return;
        }
        itemRepository.save(BookmarkCollectionItem.create(collection, bookmark));
    }

    private UUID getCurrentUserId(CustomUserPrincipal principal) {
        return postBookmarkCommandService.getCurrentUser(principal).getId();
    }

    private BookmarkMembershipResponse membershipResponse(
            UUID userId,
            UUID postId,
            BookmarkCollection collection
    ) {
        return new BookmarkMembershipResponse(
                postId,
                postBookmarkRepository.existsByPostIdAndUserId(postId, userId),
                itemRepository.findCollectionIdsContainingPost(userId, postId),
                toResponse(collection, metricFor(userId, collection.getId(), postId))
        );
    }

    private BookmarkCollection getOwnedCollection(UUID collectionId, UUID userId) {
        return collectionRepository.findByIdAndUserId(collectionId, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Bookmark collection not found."
                ));
    }

    private void ensureNameAvailable(UUID userId, String normalizedName, UUID excludedId) {
        boolean duplicate = excludedId == null
                ? collectionRepository.existsByUserIdAndNormalizedName(userId, normalizedName)
                : collectionRepository.existsByUserIdAndNormalizedNameAndIdNot(
                        userId,
                        normalizedName,
                        excludedId
                );
        if (duplicate) {
            throw duplicateName();
        }
    }

    private CollectionMetrics metricFor(UUID userId, UUID collectionId, UUID postId) {
        return queryRepository.findCollectionMetrics(userId, postId)
                .getOrDefault(collectionId, CollectionMetrics.empty());
    }

    private BookmarkCollectionResponse toResponse(
            BookmarkCollection collection,
            CollectionMetrics metrics
    ) {
        return new BookmarkCollectionResponse(
                collection.getId(),
                collection.getName(),
                metrics.coverImageUrl(),
                metrics.bookmarkCount(),
                collection.getCreatedAt(),
                collection.getUpdatedAt(),
                metrics.containsPost()
        );
    }

    private int clampSize(int requested) {
        if (requested <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    private String buildItemCursor(List<BookmarkCollectionItem> items, boolean hasNext) {
        if (!hasNext || items.isEmpty()) {
            return null;
        }
        BookmarkCollectionItem last = items.get(items.size() - 1);
        return last.getAddedAt() + "|" + last.getId();
    }

    private ResponseStatusException duplicateName() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "A bookmark collection with this name already exists."
        );
    }
}
