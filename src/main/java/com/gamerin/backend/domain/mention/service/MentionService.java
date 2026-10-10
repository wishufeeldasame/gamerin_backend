package com.gamerin.backend.domain.mention.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gamerin.backend.domain.mention.model.ParsedMention;
import com.gamerin.backend.domain.mention.repository.MentionCommandRepository;
import com.gamerin.backend.domain.mention.repository.MentionCommandRepository.MentionAttachResult;
import com.gamerin.backend.domain.notification.service.NotificationCommandService;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostComment;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;

@Service
@Transactional
public class MentionService {

    private final MentionParser mentionParser;
    private final MentionCommandRepository mentionCommandRepository;
    private final UserRepository userRepository;
    private final NotificationCommandService notificationCommandService;

    public MentionService(
            MentionParser mentionParser,
            MentionCommandRepository mentionCommandRepository,
            UserRepository userRepository,
            NotificationCommandService notificationCommandService
    ) {
        this.mentionParser = mentionParser;
        this.mentionCommandRepository = mentionCommandRepository;
        this.userRepository = userRepository;
        this.notificationCommandService = notificationCommandService;
    }

    public void attachToPost(Post post) {
        attach(post.getContent(), post, null, post.getAuthor());
    }

    public void attachToComment(PostComment comment) {
        attach(comment.getContent(), comment.getPost(), comment, comment.getAuthor());
    }

    public void removeForComment(UUID commentId) {
        mentionCommandRepository.deleteByCommentId(commentId);
    }

    private void attach(
            String content,
            Post post,
            PostComment comment,
            User actor
    ) {
        List<ParsedMention> parsedMentions = mentionParser.parse(content);
        if (parsedMentions.isEmpty()) {
            return;
        }

        Map<String, User> usersByHandle = findUsersByHandle(parsedMentions);
        Map<UUID, User> uniqueRecipients = resolveRecipients(parsedMentions, usersByHandle);

        for (User recipient : uniqueRecipients.values()) {
            MentionAttachResult result = comment == null
                    ? mentionCommandRepository.attachToPost(post.getId(), recipient.getId())
                    : mentionCommandRepository.attachToComment(comment.getId(), recipient.getId());
            if (!result.created() || actor.getId().equals(recipient.getId())) {
                continue;
            }
            if (comment != null && post.getAuthor().getId().equals(recipient.getId())) {
                continue;
            }
            notificationCommandService.createMention(
                    result.mention(),
                    post,
                    actor,
                    recipient
            );
        }
    }

    private Map<String, User> findUsersByHandle(List<ParsedMention> mentions) {
        Set<String> lookupHandles = new LinkedHashSet<>();
        for (ParsedMention mention : mentions) {
            lookupHandles.addAll(mention.lookupCandidates());
        }

        Map<String, User> usersByHandle = new LinkedHashMap<>();
        for (User user : userRepository.findActiveByHandleIn(lookupHandles)) {
            usersByHandle.put(user.getHandle(), user);
        }
        return usersByHandle;
    }

    private Map<UUID, User> resolveRecipients(
            List<ParsedMention> mentions,
            Map<String, User> usersByHandle
    ) {
        Map<UUID, User> recipients = new LinkedHashMap<>();
        for (ParsedMention mention : mentions) {
            for (String candidate : mention.lookupCandidates()) {
                User user = usersByHandle.get(candidate);
                if (user != null) {
                    recipients.putIfAbsent(user.getId(), user);
                    break;
                }
            }
        }
        return recipients;
    }
}
