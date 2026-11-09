package com.gamerin.backend.domain.upload.dto.response;

import java.util.UUID;

import com.gamerin.backend.domain.message.dto.response.MessageResponse;
import com.gamerin.backend.domain.post.dto.response.PostDetailResponse;

public record UploadJobResponse(UUID jobId, String status, String errorCode, String errorMessage,
        PostDetailResponse post, MessageResponse message) {}
