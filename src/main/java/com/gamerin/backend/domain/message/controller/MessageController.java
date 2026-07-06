package com.gamerin.backend.domain.message.controller;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.gamerin.backend.domain.message.dto.request.CreateConversationRequest;
import com.gamerin.backend.domain.message.dto.request.SendMessageRequest;
import com.gamerin.backend.domain.message.dto.request.SendMultipartMessageRequest;
import com.gamerin.backend.domain.message.dto.request.SharePostMessageRequest;
import com.gamerin.backend.domain.message.dto.response.ConversationResponse;
import com.gamerin.backend.domain.message.dto.response.MessageRecipientResponse;
import com.gamerin.backend.domain.message.dto.response.MessageResponse;
import com.gamerin.backend.domain.message.dto.response.MessageStreamTokenResponse;
import com.gamerin.backend.domain.message.service.MessageService;
import com.gamerin.backend.domain.message.service.MessageService.MessageAttachmentFile;
import com.gamerin.backend.global.response.ApiResponse;
import com.gamerin.backend.global.response.CursorPageResponse;
import com.gamerin.backend.global.security.jwt.SseStreamTokenService;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/messages")
@SecurityRequirement(name = "bearerAuth")
public class MessageController {

    private final MessageService messageService;
    private final SseStreamTokenService sseStreamTokenService;

    public MessageController(MessageService messageService, SseStreamTokenService sseStreamTokenService) {
        this.messageService = messageService;
        this.sseStreamTokenService = sseStreamTokenService;
    }

    @GetMapping("/conversations")
    public ApiResponse<List<ConversationResponse>> getConversations(
            @AuthenticationPrincipal CustomUserPrincipal principal
    ) {
        return ApiResponse.ok(messageService.getConversations(principal));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessages(
            @AuthenticationPrincipal CustomUserPrincipal principal
    ) {
        return messageService.streamMessages(principal);
    }

    @PostMapping("/stream-token")
    public ApiResponse<MessageStreamTokenResponse> issueStreamToken(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            HttpServletResponse response
    ) {
        SseStreamTokenService.IssuedToken issuedToken = messageService.issueStreamToken(principal);
        ResponseCookie cookie = sseStreamTokenService.createCookie(issuedToken);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return ApiResponse.ok(new MessageStreamTokenResponse(
                OffsetDateTime.ofInstant(issuedToken.expiresAt(), ZoneOffset.UTC)
        ));
    }

    @GetMapping("/attachments/{attachmentId}")
    public ResponseEntity<Resource> getMessageAttachment(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID attachmentId
    ) {
        MessageAttachmentFile attachmentFile = messageService.getMessageAttachmentFile(principal, attachmentId);
        ContentDisposition contentDisposition = ContentDisposition.inline()
                .filename(attachmentFile.fileName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(attachmentFile.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .body(new PathResource(attachmentFile.path()));
    }

    @PostMapping("/conversations")
    public ApiResponse<ConversationResponse> createConversation(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @Valid @RequestBody CreateConversationRequest request
    ) {
        return ApiResponse.ok(messageService.createConversation(principal, request));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public ApiResponse<CursorPageResponse<MessageResponse>> getMessages(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID conversationId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "30") int size
    ) {
        return ApiResponse.ok(messageService.getMessages(principal, conversationId, cursor, size));
    }

    @PostMapping(
            value = "/conversations/{conversationId}/messages",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ApiResponse<MessageResponse> sendMessage(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID conversationId,
            @Valid @RequestBody SendMessageRequest request
    ) {
        return ApiResponse.ok(messageService.sendMessage(principal, conversationId, request));
    }

    @PostMapping(
            value = "/conversations/{conversationId}/messages",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ApiResponse<MessageResponse> sendMultipartMessage(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID conversationId,
            @Valid @ModelAttribute SendMultipartMessageRequest request
    ) {
        return ApiResponse.ok(messageService.sendMultipartMessage(principal, conversationId, request));
    }

    @DeleteMapping("/conversations/{conversationId}/messages/{messageId}")
    public ApiResponse<Void> deleteMessage(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID conversationId,
            @PathVariable UUID messageId
    ) {
        messageService.deleteMessage(principal, conversationId, messageId);
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/conversations/{conversationId}")
    public ApiResponse<Void> leaveConversation(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID conversationId
    ) {
        messageService.leaveConversation(principal, conversationId);
        return ApiResponse.ok(null);
    }

    @PatchMapping("/conversations/{conversationId}/read")
    public ApiResponse<Void> markRead(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable UUID conversationId
    ) {
        messageService.markRead(principal, conversationId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/recipients")
    public ApiResponse<List<MessageRecipientResponse>> searchRecipients(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(messageService.searchRecipients(principal, keyword, size));
    }

    @PostMapping("/share-post")
    public ApiResponse<List<ConversationResponse>> sharePost(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @Valid @RequestBody SharePostMessageRequest request
    ) {
        return ApiResponse.ok(messageService.sharePost(principal, request));
    }
}
