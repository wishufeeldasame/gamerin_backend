package com.gamerin.backend.domain.game.service;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserProfileRepository;

@Service
@Transactional
public class GameStatsPersistenceService {

    private final UserProfileRepository userProfileRepository;

    public GameStatsPersistenceService(UserProfileRepository userProfileRepository) {
        this.userProfileRepository = userProfileRepository;
    }

    public void updateConnection(UUID userId, Consumer<UserProfile> update) {
        update.accept(lockProfile(userId));
    }

    public UserProfile updateSummary(
            UUID userId,
            String connectionKey,
            long expectedConnectionVersion,
            Predicate<UserProfile> sameAccount,
            Consumer<UserProfile> update
    ) {
        UserProfile current = lockProfile(userId);
        // The row lock protects both the connection check and the subsequent JSON write.
        if (current.getGameConnectionVersion(connectionKey) == expectedConnectionVersion
                && sameAccount.test(current)) {
            update.accept(current);
        }
        return current;
    }

    private UserProfile lockProfile(UUID userId) {
        return userProfileRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "User profile is not initialized."));
    }
}
