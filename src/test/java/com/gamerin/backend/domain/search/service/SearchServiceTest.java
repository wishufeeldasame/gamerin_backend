package com.gamerin.backend.domain.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.gamerin.backend.domain.hashtag.repository.HashtagQueryRepository;
import com.gamerin.backend.domain.hashtag.service.HashtagParser;
import com.gamerin.backend.domain.membership.service.MembershipService;
import com.gamerin.backend.domain.post.service.PostResponseAssembler;
import com.gamerin.backend.domain.search.repository.SearchQueryRepository;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {
    @Mock private SearchQueryRepository searchQueryRepository;
    @Mock private HashtagQueryRepository hashtagQueryRepository;
    @Mock private HashtagParser hashtagParser;
    @Mock private PostResponseAssembler postResponseAssembler;
    @Mock private UserRepository userRepository;
    @Mock private MembershipService membershipService;
    @InjectMocks private SearchService service;
    private CustomUserPrincipal principal;

    @BeforeEach
    void authenticateViewer() {
        User viewer = user("viewer");
        principal = CustomUserPrincipal.from(viewer);
        when(userRepository.findByIdAndDeletedAtIsNull(viewer.getId())).thenReturn(Optional.of(viewer));
    }

    @Test
    void accountSearchBatchesOnlyVisibleUsersAndKeepsTheCursor() {
        User active = user("account1");
        User inactive = user("account2");
        User nextPage = user("account3");
        when(searchQueryRepository.findActiveAccounts(eq("account"), isNull(), anyInt()))
                .thenReturn(List.of(active, inactive, nextPage));
        when(membershipService.findActiveUserIds(List.of(active.getId(), inactive.getId())))
                .thenReturn(Set.of(active.getId()));

        var response = service.searchAccounts(principal, "account", null, 2);

        assertThat(response.items()).extracting(item -> item.membershipBadge()).containsExactly(true, false);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isNotBlank();
        verify(membershipService).findActiveUserIds(List.of(active.getId(), inactive.getId()));
        verifyNoMoreInteractions(membershipService);
    }

    @Test
    void overviewAccountSectionUsesTheSameBulkMembershipMapping() {
        User active = user("account");
        when(searchQueryRepository.findActiveAccounts(eq("account"), isNull(), anyInt())).thenReturn(List.of(active));
        when(membershipService.findActiveUserIds(List.of(active.getId()))).thenReturn(Set.of(active.getId()));

        var response = service.overview(principal, "account", 5);

        assertThat(response.accounts().items()).singleElement().satisfies(item -> assertThat(item.membershipBadge()).isTrue());
        verify(membershipService).findActiveUserIds(List.of(active.getId()));
        verifyNoMoreInteractions(membershipService);
    }

    @Test
    void emptyAccountResultsDoNotQueryMembership() {
        assertThat(service.searchAccounts(principal, "account", null, 2).items()).isEmpty();
        assertThat(service.overview(principal, "account", 5).accounts().items()).isEmpty();
        verifyNoInteractions(membershipService);
    }

    private User user(String handle) {
        User user = User.createLocal(handle + "@example.com", handle, handle, "encoded");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }
}
