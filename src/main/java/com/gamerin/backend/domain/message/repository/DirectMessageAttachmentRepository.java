package com.gamerin.backend.domain.message.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gamerin.backend.domain.message.entity.DirectMessageAttachment;

public interface DirectMessageAttachmentRepository extends JpaRepository<DirectMessageAttachment, UUID> {

    @Query("""
        select dma
        from DirectMessageAttachment dma
        where dma.message.id in :messageIds
        order by dma.message.id asc, dma.sortOrder asc, dma.id asc
        """)
    List<DirectMessageAttachment> findByMessageIds(@Param("messageIds") Collection<UUID> messageIds);

    @Query("""
        select dma
        from DirectMessageAttachment dma
        join fetch dma.message dm
        join fetch dm.conversation
        where dma.id = :attachmentId
        """)
    Optional<DirectMessageAttachment> findByIdWithMessage(@Param("attachmentId") UUID attachmentId);
}
