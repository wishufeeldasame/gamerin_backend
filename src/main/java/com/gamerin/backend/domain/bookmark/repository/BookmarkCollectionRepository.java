package com.gamerin.backend.domain.bookmark.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gamerin.backend.domain.bookmark.entity.BookmarkCollection;

public interface BookmarkCollectionRepository extends JpaRepository<BookmarkCollection, UUID> {

    List<BookmarkCollection> findAllByUserIdOrderByCreatedAtDescIdDesc(UUID userId);

    Optional<BookmarkCollection> findByIdAndUserId(UUID id, UUID userId);

    long countByUserId(UUID userId);

    boolean existsByUserIdAndNormalizedName(UUID userId, String normalizedName);

    boolean existsByUserIdAndNormalizedNameAndIdNot(
            UUID userId,
            String normalizedName,
            UUID excludedId
    );
}
