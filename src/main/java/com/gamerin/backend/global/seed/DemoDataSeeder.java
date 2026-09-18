package com.gamerin.backend.global.seed;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.gamerin.backend.domain.bookmark.dto.request.CreateBookmarkCollectionRequest;
import com.gamerin.backend.domain.bookmark.service.BookmarkCollectionService;
import com.gamerin.backend.domain.follow.service.FollowService;
import com.gamerin.backend.domain.game.model.GameStatsMode;
import com.gamerin.backend.domain.hashtag.service.HashtagService;
import com.gamerin.backend.domain.mention.service.MentionService;
import com.gamerin.backend.domain.mentoring.dto.request.MentorRegistrationRequest;
import com.gamerin.backend.domain.mentoring.dto.request.MentoringApplicationRequest;
import com.gamerin.backend.domain.mentoring.dto.request.MentoringProgramRequest;
import com.gamerin.backend.domain.mentoring.dto.request.MentoringReviewRequest;
import com.gamerin.backend.domain.mentoring.service.MentoringService;
import com.gamerin.backend.domain.message.dto.request.CreateConversationRequest;
import com.gamerin.backend.domain.message.dto.request.SendMessageRequest;
import com.gamerin.backend.domain.message.entity.DirectMessage;
import com.gamerin.backend.domain.message.entity.MessageConversation;
import com.gamerin.backend.domain.message.repository.DirectMessageRepository;
import com.gamerin.backend.domain.message.repository.MessageConversationRepository;
import com.gamerin.backend.domain.message.repository.MessageParticipantRepository;
import com.gamerin.backend.domain.message.service.MessageService;
import com.gamerin.backend.domain.notification.service.NotificationCommandService;
import com.gamerin.backend.domain.post.dto.request.CreateShareRequest;
import com.gamerin.backend.domain.post.entity.Post;
import com.gamerin.backend.domain.post.entity.PostComment;
import com.gamerin.backend.domain.post.entity.ShareTarget;
import com.gamerin.backend.domain.post.repository.PostCommentRepository;
import com.gamerin.backend.domain.post.repository.PostRepository;
import com.gamerin.backend.domain.post.service.PostService;
import com.gamerin.backend.domain.repost.service.PostRepostService;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.user.service.MileageService;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

/**
 * 로컬·데모 환경 전용 더미 데이터 시더.
 * seed 프로필과 app.seed.enabled=true 가 모두 있어야 등록되며, 전체를 한 트랜잭션으로 생성한다.
 * 게시물·댓글·텍스트 DM 본문은 고정된 시드 문구이므로 외부 검열 API를 거치지 않는다.
 */
@Component
@Profile("seed")
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    public static final int USER_COUNT = 20;
    public static final int POSTS_PER_USER = 5;
    public static final int MENTOR_COUNT = 5;
    public static final int PROGRAMS_PER_MENTOR = 2;
    public static final long INITIAL_MILEAGE = 100_000L;
    public static final String PASSWORD = "Demo1234!";

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final String[] GAMES = {"PUBG", "R6", "LOL"};
    private static final String[] TIERS = {"Bronze", "Silver", "Gold", "Platinum", "Diamond"};

    private final UserRepository userRepository;
    private final PostRepository postRepository;
    private final PostCommentRepository postCommentRepository;
    private final MessageConversationRepository messageConversationRepository;
    private final MessageParticipantRepository messageParticipantRepository;
    private final DirectMessageRepository directMessageRepository;
    private final PasswordEncoder passwordEncoder;
    private final HashtagService hashtagService;
    private final MentionService mentionService;
    private final NotificationCommandService notificationCommandService;
    private final PostService postService;
    private final FollowService followService;
    private final PostRepostService postRepostService;
    private final BookmarkCollectionService bookmarkCollectionService;
    private final MessageService messageService;
    private final MileageService mileageService;
    private final MentoringService mentoringService;

    public DemoDataSeeder(
            UserRepository userRepository,
            PostRepository postRepository,
            PostCommentRepository postCommentRepository,
            MessageConversationRepository messageConversationRepository,
            MessageParticipantRepository messageParticipantRepository,
            DirectMessageRepository directMessageRepository,
            PasswordEncoder passwordEncoder,
            HashtagService hashtagService,
            MentionService mentionService,
            NotificationCommandService notificationCommandService,
            PostService postService,
            FollowService followService,
            PostRepostService postRepostService,
            BookmarkCollectionService bookmarkCollectionService,
            MessageService messageService,
            MileageService mileageService,
            MentoringService mentoringService
    ) {
        this.userRepository = userRepository;
        this.postRepository = postRepository;
        this.postCommentRepository = postCommentRepository;
        this.messageConversationRepository = messageConversationRepository;
        this.messageParticipantRepository = messageParticipantRepository;
        this.directMessageRepository = directMessageRepository;
        this.passwordEncoder = passwordEncoder;
        this.hashtagService = hashtagService;
        this.mentionService = mentionService;
        this.notificationCommandService = notificationCommandService;
        this.postService = postService;
        this.followService = followService;
        this.postRepostService = postRepostService;
        this.bookmarkCollectionService = bookmarkCollectionService;
        this.messageService = messageService;
        this.mileageService = mileageService;
        this.mentoringService = mentoringService;
    }

    public static String handle(int index) {
        return String.format("demo%02d", index + 1);
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        long existing = 0;
        for (int i = 0; i < USER_COUNT; i++) {
            if (userRepository.existsByHandle(handle(i))) {
                existing++;
            }
        }
        if (existing == USER_COUNT) {
            log.info("Demo seed skipped: demo01~demo20 already exist.");
            return;
        }
        if (existing > 0) {
            throw new IllegalStateException(
                    "Demo seed aborted: only " + existing + " of demo01~demo20 exist. Resolve manually; nothing was changed."
            );
        }

        List<User> users = createUsers();
        List<CustomUserPrincipal> principals = users.stream().map(CustomUserPrincipal::from).toList();
        List<Post> posts = createPosts(users);

        for (int i = 0; i < USER_COUNT; i++) {
            CustomUserPrincipal me = principals.get(i);
            for (int k = 1; k <= 5; k++) {
                followService.follow(me, handle((i + k) % USER_COUNT));
            }
            for (int k = 1; k <= 10; k++) {
                postService.like(me, postOf(posts, i + k, i + k).getId());
            }
            for (int k = 1; k <= 3; k++) {
                createComment(users.get(i), postOf(posts, i + k, i), handle((i + k + 4) % USER_COUNT));
            }
            for (int k = 11; k <= 13; k++) {
                postRepostService.repost(me, postOf(posts, i + k, i + 1).getId());
            }
            for (int k = 14; k <= 15; k++) {
                postService.share(me, postOf(posts, i + k, i + 2).getId(),
                        new CreateShareRequest(k == 14 ? ShareTarget.COPY_LINK : ShareTarget.KAKAO));
            }
            seedBookmarks(me, posts, i);
        }

        seedDirectMessages(users, principals, posts);
        seedMentoring(users, principals);
        log.info("Demo seed completed: {} users, {} posts.", users.size(), posts.size());
    }

    private List<User> createUsers() {
        String passwordHash = passwordEncoder.encode(PASSWORD);
        List<User> users = new ArrayList<>();
        for (int i = 0; i < USER_COUNT; i++) {
            String handle = handle(i);
            User user = User.createLocal(handle + "@gamerin-demo.test", handle, "데모유저" + (i + 1), passwordHash);
            UserProfile profile = UserProfile.createDefault(user);
            profile.updateBio("GamerIN 데모 계정 " + handle + " 입니다.");
            profile.updateLocation("Seoul");
            profile.updatePubgSummary(TIERS[i % TIERS.length], 1.0 + i / 10.0, 10 + i, 50 + i * 3, GameStatsMode.RANKED);
            profile.updateR6Summary(TIERS[(i + 1) % TIERS.length], 0.8 + i / 20.0, 40 + i, 30 + i, GameStatsMode.NORMAL, null);
            profile.updateLolSummary(TIERS[(i + 2) % TIERS.length] + " II", 2.0 + i / 10.0, 45 + i, 80 + i);
            user.setProfile(profile);
            User saved = userRepository.save(user);
            mileageService.chargeMileage(saved, INITIAL_MILEAGE);
            users.add(saved);
        }
        return users;
    }

    private List<Post> createPosts(List<User> users) {
        List<Post> posts = new ArrayList<>();
        for (int i = 0; i < USER_COUNT; i++) {
            for (int p = 0; p < POSTS_PER_USER; p++) {
                String game = GAMES[(i + p) % GAMES.length];
                String content = "오늘 " + game + " 랭크 " + (p + 3) + "판 달렸습니다. @"
                        + handle((i + p + 1) % USER_COUNT) + " 다음엔 같이 해요! #" + game + " #GamerIN";
                Post post = postRepository.save(Post.create(users.get(i), content));
                hashtagService.attachToPost(post);
                mentionService.attachToPost(post);
                posts.add(post);
            }
        }
        return posts;
    }

    private void createComment(User author, Post post, String mentionedHandle) {
        PostComment comment = postCommentRepository.save(
                PostComment.create(post, author, "좋은 플레이네요! @" + mentionedHandle + " 도 한번 보세요.")
        );
        post.increaseCommentCount();
        notificationCommandService.createComment(comment, post, author);
        mentionService.attachToComment(comment);
    }

    private void seedBookmarks(CustomUserPrincipal me, List<Post> posts, int i) {
        UUID first = bookmarkCollectionService.create(me, new CreateBookmarkCollectionRequest("공략 모음", null)).collectionId();
        UUID second = bookmarkCollectionService.create(me, new CreateBookmarkCollectionRequest("하이라이트", null)).collectionId();
        for (int k = 1; k <= 8; k++) {
            UUID postId = postOf(posts, i + k, i + 3).getId();
            if (k <= 3) {
                bookmarkCollectionService.addPost(me, first, postId);
            } else if (k <= 6) {
                bookmarkCollectionService.addPost(me, second, postId);
            } else {
                postService.bookmark(me, postId);
            }
        }
    }

    private void seedDirectMessages(List<User> users, List<CustomUserPrincipal> principals, List<Post> posts) {
        int conversationIndex = 0;
        for (int gap = 1; gap <= 2; gap++) {
            for (int a = 0; a < USER_COUNT; a++, conversationIndex++) {
                int b = (a + gap) % USER_COUNT;
                UUID conversationId = messageService
                        .createConversation(principals.get(a), new CreateConversationRequest(null, users.get(b).getId()))
                        .id();
                MessageConversation conversation = messageConversationRepository.findById(conversationId).orElseThrow();

                sendText(conversation, users.get(a), users.get(b), "안녕하세요 " + handle(b) + "님, 같이 게임하실래요?");
                sendText(conversation, users.get(b), users.get(a), "좋아요! 오늘 저녁 어떠세요?");
                messageService.sendMessage(principals.get(a), conversationId,
                        new SendMessageRequest(null, postOf(posts, b, conversationIndex).getId()));
                sendText(conversation, users.get(b), users.get(a), "공유해주신 글 잘 봤어요.");

                if (conversationIndex % 2 == 0) {
                    messageService.markRead(principals.get(a), conversationId);
                }
            }
        }
    }

    // MessageService.sendMessage 는 텍스트를 외부 검열 API로 보내므로, 동일한 저장·알림 흐름을 직접 수행한다.
    private void sendText(MessageConversation conversation, User sender, User recipient, String content) {
        DirectMessage message = directMessageRepository.save(DirectMessage.create(conversation, sender, content, null));
        conversation.updateLastMessage(message.getId());
        notificationCommandService.createOrRefreshDirectMessage(recipient, sender, conversation, message);
        messageParticipantRepository
                .findByConversationIdAndUserIdAndDeletedAtIsNull(conversation.getId(), sender.getId())
                .orElseThrow()
                .markRead();
        notificationCommandService.markDirectMessageRead(sender.getId(), conversation.getId());
    }

    private void seedMentoring(List<User> users, List<CustomUserPrincipal> principals) {
        List<UUID> programIds = new ArrayList<>();
        for (int m = 0; m < MENTOR_COUNT; m++) {
            mentoringService.registerMentor(principals.get(m), new MentorRegistrationRequest(handle(m) + " 멘토입니다."));
            for (int p = 0; p < PROGRAMS_PER_MENTOR; p++) {
                String game = GAMES[(m + p) % GAMES.length];
                programIds.add(mentoringService.registerProgram(principals.get(m), new MentoringProgramRequest(
                        game, game + " 실력 향상 코칭 " + (p + 1), "리플레이 분석과 1:1 피드백을 제공합니다.",
                        "평일 저녁 협의", 5_000L + (m * 2 + p) * 1_000L, List.of(game, "데모")
                )).id());
            }
        }

        for (int i = 0; i < USER_COUNT; i++) {
            CustomUserPrincipal mentee = principals.get(i);
            // 프로그램 (2i+2)%10 의 멘토는 (i+1)%5 이므로 멘토 자신의 프로그램에는 신청하지 않는다.
            UUID programId = programIds.get((2 * i + 2) % programIds.size());
            CustomUserPrincipal mentor = principals.get((i + 1) % MENTOR_COUNT);
            UUID applicationId = mentoringService
                    .applyToProgram(mentee, new MentoringApplicationRequest(programId, "잘 부탁드립니다."))
                    .id();

            switch (i % 7) {
                case 0 -> { } // APPLIED / ESCROW_HELD
                case 1 -> mentoringService.acceptApplication(mentor, applicationId);
                case 2 -> mentoringService.rejectApplication(mentor, applicationId);
                case 3 -> mentoringService.cancelApplication(mentee, applicationId);
                case 4 -> {
                    mentoringService.acceptApplication(mentor, applicationId);
                    mentoringService.startMentoring(mentor, applicationId);
                }
                case 5 -> {
                    mentoringService.acceptApplication(mentor, applicationId);
                    mentoringService.startMentoring(mentor, applicationId);
                    mentoringService.finishMentoring(mentor, applicationId);
                }
                default -> {
                    mentoringService.acceptApplication(mentor, applicationId);
                    mentoringService.startMentoring(mentor, applicationId);
                    mentoringService.finishMentoring(mentor, applicationId);
                    mentoringService.completeMentoring(mentee, applicationId);
                    mentoringService.createReview(mentee,
                            new MentoringReviewRequest(applicationId, 4 + i % 2, "덕분에 많이 늘었어요!"));
                }
            }
        }
    }

    private static Post postOf(List<Post> posts, int userIndex, int postIndex) {
        return posts.get((userIndex % USER_COUNT) * POSTS_PER_USER + Math.floorMod(postIndex, POSTS_PER_USER));
    }
}
