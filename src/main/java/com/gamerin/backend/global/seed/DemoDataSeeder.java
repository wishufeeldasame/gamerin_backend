package com.gamerin.backend.global.seed;

import static com.gamerin.backend.global.seed.DemoSeedData.APPLICATIONS;
import static com.gamerin.backend.global.seed.DemoSeedData.BOOKMARK_COLLECTIONS;
import static com.gamerin.backend.global.seed.DemoSeedData.COMMENTS;
import static com.gamerin.backend.global.seed.DemoSeedData.CONVERSATIONS;
import static com.gamerin.backend.global.seed.DemoSeedData.MENTORS;
import static com.gamerin.backend.global.seed.DemoSeedData.MENTOR_ABOUTS;
import static com.gamerin.backend.global.seed.DemoSeedData.POSTS;
import static com.gamerin.backend.global.seed.DemoSeedData.PROGRAMS;
import static com.gamerin.backend.global.seed.DemoSeedData.USERS;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
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
import com.gamerin.backend.domain.mentoring.entity.MentorStatus;
import com.gamerin.backend.domain.mentoring.repository.MentorProfileRepository;
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
import com.gamerin.backend.domain.user.entity.TransactionType;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.user.service.MileageService;
import com.gamerin.backend.global.seed.DemoSeedData.DemoApplication;
import com.gamerin.backend.global.seed.DemoSeedData.DemoProgram;
import com.gamerin.backend.global.seed.DemoSeedData.DemoUser;
import com.gamerin.backend.global.seed.DemoSeedData.DmConversation;
import com.gamerin.backend.global.seed.DemoSeedData.DmLine;
import com.gamerin.backend.global.security.principal.CustomUserPrincipal;

/**
 * 로컬·데모 환경 전용 더미 데이터 시더.
 * seed 프로필과 app.seed.enabled=true 가 모두 있어야 등록되며, 전체를 한 트랜잭션으로 생성한다.
 * 게시물·댓글·텍스트 DM 본문은 고정된 시드 문구({@link DemoSeedData})이므로 외부 검열 API를 거치지 않는다.
 */
@Component
@Profile("seed")
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    public static final int USER_COUNT = USERS.size();
    public static final int POSTS_PER_USER = POSTS.get(0).size();
    public static final long INITIAL_MILEAGE = 100_000L;
    public static final String PASSWORD = "Demo1234!";
    public static final String EMAIL_DOMAIN = "@gamerin-demo.test";

    /** 피드가 한 시점에 몰리지 않도록 게시물 작성 시각을 이 간격으로 과거에 분산한다. */
    private static final int POST_INTERVAL_MINUTES = 95;

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

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
    private final MentorProfileRepository mentorProfileRepository;
    private final JdbcTemplate jdbcTemplate;

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
            MentoringService mentoringService,
            MentorProfileRepository mentorProfileRepository,
            JdbcTemplate jdbcTemplate
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
        this.mentorProfileRepository = mentorProfileRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public static String handle(int index) {
        return USERS.get(index).handle();
    }

    /** 로그인 이메일. demo01@gamerin-demo.test ~ demo10@gamerin-demo.test */
    public static String email(int index) {
        return String.format("demo%02d", index + 1) + EMAIL_DOMAIN;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        long existingHandles = 0;
        long existingEmails = 0;
        for (int i = 0; i < USER_COUNT; i++) {
            if (userRepository.existsByHandle(handle(i))) {
                existingHandles++;
            }
            if (userRepository.existsByEmail(email(i))) {
                existingEmails++;
            }
        }
        if (existingHandles == USER_COUNT && existingEmails == USER_COUNT) {
            log.info("Demo seed skipped: {} demo users already exist.", USER_COUNT);
            return;
        }
        if (existingHandles > 0 || existingEmails > 0) {
            throw new IllegalStateException("Demo seed aborted: " + existingHandles + " handles and " + existingEmails
                    + " emails of the " + USER_COUNT + " demo users already exist (possibly an older demo seed)."
                    + " Reset the database and run again; nothing was changed.");
        }

        List<User> users = createUsers();
        List<CustomUserPrincipal> principals = users.stream().map(CustomUserPrincipal::from).toList();
        List<Post> posts = createPosts(users);

        for (int i = 0; i < USER_COUNT; i++) {
            CustomUserPrincipal me = principals.get(i);
            for (int k = 1; k <= 4; k++) {
                followService.follow(me, handle(other(i, k)));
                postService.like(me, postOf(posts, other(i, k), 0).getId());
                postService.like(me, postOf(posts, other(i, k), 1).getId());
            }
            postRepostService.repost(me, postOf(posts, other(i, 5), 0).getId());
            postRepostService.repost(me, postOf(posts, other(i, 6), 1).getId());
            postService.share(me, postOf(posts, other(i, 7), 2).getId(),
                    new CreateShareRequest(i % 2 == 0 ? ShareTarget.COPY_LINK : ShareTarget.KAKAO));
            seedBookmarks(me, posts, i);
        }
        seedComments(users, posts);
        seedDirectMessages(users, principals, posts);
        seedMentoring(principals);
        spreadPostCreatedAt(posts);
        log.info("Demo seed completed: {} users, {} posts.", users.size(), posts.size());
    }

    private List<User> createUsers() {
        String passwordHash = passwordEncoder.encode(PASSWORD);
        List<User> users = new ArrayList<>();
        for (int i = 0; i < USER_COUNT; i++) {
            DemoUser demo = USERS.get(i);
            User user = User.createLocal(email(i), demo.handle(), demo.nickname(), passwordHash);
            UserProfile profile = UserProfile.createDefault(user);
            profile.updateBio(demo.bio());
            profile.updateLocation(demo.location());
            profile.updatePubgSummary(demo.pubgTier(), 0.9 + i * 0.17, 4 + i % 7, 60 + i * 9, GameStatsMode.RANKED);
            profile.updateR6Summary(demo.r6Tier(), 0.8 + i * 0.09, 42 + i % 9, 35 + i * 7, GameStatsMode.RANKED, null);
            profile.updateLolSummary(demo.lolTier(), 2.1 + i * 0.23, 46 + i % 8, 80 + i * 11);
            user.setProfile(profile);
            User saved = userRepository.save(user);
            // 결제 없이 지급하는 데모 초기 마일리지. 결제 참조가 없어 reference_id는 비운다.
            mileageService.addMileage(saved, INITIAL_MILEAGE, TransactionType.CHARGE, "데모 초기 마일리지", null);
            users.add(saved);
        }
        return users;
    }

    // 피드에서 여러 사용자의 글이 섞여 보이도록 라운드 단위로 작성한다. 목록은 사용자·라운드 순서로 담는다.
    private List<Post> createPosts(List<User> users) {
        Post[] posts = new Post[USER_COUNT * POSTS_PER_USER];
        for (int round = 0; round < POSTS_PER_USER; round++) {
            for (int i = 0; i < USER_COUNT; i++) {
                Post post = postRepository.save(Post.create(users.get(i), POSTS.get(i).get(round)));
                hashtagService.attachToPost(post);
                mentionService.attachToPost(post);
                posts[i * POSTS_PER_USER + round] = post;
            }
        }
        return List.of(posts);
    }

    private void seedComments(List<User> users, List<Post> posts) {
        for (int i = 0; i < USER_COUNT; i++) {
            for (int round = 0; round < POSTS_PER_USER; round++) {
                User author = users.get(other(i, round + 1));
                Post post = postOf(posts, i, round);
                PostComment comment = postCommentRepository.save(
                        PostComment.create(post, author, COMMENTS.get(i).get(round)));
                post.increaseCommentCount();
                notificationCommandService.createComment(comment, post, author);
                mentionService.attachToComment(comment);
            }
        }
    }

    private void seedBookmarks(CustomUserPrincipal me, List<Post> posts, int i) {
        UUID guides = bookmarkCollectionService
                .create(me, new CreateBookmarkCollectionRequest(BOOKMARK_COLLECTIONS.get(0), null)).collectionId();
        UUID later = bookmarkCollectionService
                .create(me, new CreateBookmarkCollectionRequest(BOOKMARK_COLLECTIONS.get(1), null)).collectionId();
        bookmarkCollectionService.addPost(me, guides, postOf(posts, other(i, 1), 2).getId());
        bookmarkCollectionService.addPost(me, guides, postOf(posts, other(i, 2), 2).getId());
        bookmarkCollectionService.addPost(me, later, postOf(posts, other(i, 3), 0).getId());
        bookmarkCollectionService.addPost(me, later, postOf(posts, other(i, 4), 1).getId());
        postService.bookmark(me, postOf(posts, other(i, 8), 0).getId());
    }

    private void seedDirectMessages(List<User> users, List<CustomUserPrincipal> principals, List<Post> posts) {
        for (DmConversation dm : CONVERSATIONS) {
            UUID conversationId = messageService
                    .createConversation(principals.get(dm.a()), new CreateConversationRequest(null, users.get(dm.b()).getId()))
                    .id();
            MessageConversation conversation = messageConversationRepository.findById(conversationId).orElseThrow();

            boolean lastFromA = true;
            for (DmLine line : dm.lines()) {
                int sender = line.fromA() ? dm.a() : dm.b();
                int recipient = line.fromA() ? dm.b() : dm.a();
                if (line.sharedPost() != null) {
                    Post shared = postOf(posts, line.sharedPost().user(), line.sharedPost().round());
                    messageService.sendMessage(principals.get(sender), conversationId,
                            new SendMessageRequest(null, shared.getId()));
                } else {
                    sendText(conversation, users.get(sender), users.get(recipient), line.text());
                }
                lastFromA = line.fromA();
            }
            if (dm.lastRecipientReads()) {
                messageService.markRead(principals.get(lastFromA ? dm.b() : dm.a()), conversationId);
            }
        }
    }

    // MessageService.sendMessage 는 텍스트를 외부 검열 API로 보내므로, 같은 저장·알림·읽음 흐름을 직접 수행한다.
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

    private void seedMentoring(List<CustomUserPrincipal> principals) {
        for (int m = 0; m < MENTORS.size(); m++) {
            CustomUserPrincipal mentor = principals.get(MENTORS.get(m));
            mentoringService.registerMentor(mentor, new MentorRegistrationRequest(MENTOR_ABOUTS.get(m)));
            // 프로그램 등록은 ACTIVE 멘토만 가능하므로 관리자 승인을 시드에서 대신한다.
            mentorProfileRepository.findById(mentor.getUserId()).orElseThrow().setStatus(MentorStatus.ACTIVE);
        }

        List<UUID> programIds = new ArrayList<>();
        for (DemoProgram program : PROGRAMS) {
            programIds.add(mentoringService.registerProgram(principals.get(program.mentor()), new MentoringProgramRequest(
                    program.gameType(), program.title(), program.content(), program.availableTime(),
                    program.price(), program.tags()
            )).id());
        }

        for (DemoApplication demo : APPLICATIONS) {
            CustomUserPrincipal mentee = principals.get(demo.mentee());
            CustomUserPrincipal mentor = principals.get(PROGRAMS.get(demo.program()).mentor());
            UUID applicationId = mentoringService
                    .applyToProgram(mentee, new MentoringApplicationRequest(programIds.get(demo.program()), demo.message()))
                    .id();

            switch (demo.outcome()) {
                case APPLIED -> { } // ESCROW_HELD 그대로 둔다.
                case CANCELLED -> mentoringService.cancelApplication(mentee, applicationId);
                case REJECTED -> mentoringService.rejectApplication(mentor, applicationId);
                default -> {
                    mentoringService.acceptApplication(mentor, applicationId);
                    switch (demo.outcome()) {
                        case ACCEPTED -> { }
                        case ONGOING -> mentoringService.startMentoring(mentor, applicationId);
                        case FINISHED -> {
                            mentoringService.startMentoring(mentor, applicationId);
                            mentoringService.finishMentoring(mentor, applicationId);
                        }
                        case COMPLETED -> {
                            mentoringService.startMentoring(mentor, applicationId);
                            mentoringService.finishMentoring(mentor, applicationId);
                            mentoringService.completeMentoring(mentee, applicationId);
                            mentoringService.createReview(mentee,
                                    new MentoringReviewRequest(applicationId, demo.rating(), demo.review()));
                        }
                        default -> throw new IllegalStateException("Unsupported demo outcome: " + demo.outcome());
                    }
                }
            }
        }
    }

    /**
     * Post 의 created_at 은 수정 불가 컬럼이라 엔티티로 바꿀 수 없어, 모든 쓰기를 flush 한 뒤 SQL로 과거 시각을 지정한다.
     * 작성 순서(라운드 → 사용자)대로 오래된 글이 되도록 POST_INTERVAL_MINUTES 간격으로 배치한다.
     */
    private void spreadPostCreatedAt(List<Post> posts) {
        postRepository.flush();
        OffsetDateTime now = OffsetDateTime.now();
        int total = posts.size();
        List<Object[]> rows = new ArrayList<>();
        for (int round = 0; round < POSTS_PER_USER; round++) {
            for (int i = 0; i < USER_COUNT; i++) {
                int order = round * USER_COUNT + i;
                Timestamp createdAt = Timestamp.from(
                        now.minusMinutes((long) (total - order) * POST_INTERVAL_MINUTES).toInstant());
                rows.add(new Object[] {createdAt, createdAt, postOf(posts, i, round).getId()});
            }
        }
        jdbcTemplate.batchUpdate("update posts set created_at = ?, updated_at = ? where id = ?", rows);
    }

    private static int other(int index, int offset) {
        return (index + offset) % USER_COUNT;
    }

    private static Post postOf(List<Post> posts, int userIndex, int round) {
        return posts.get(userIndex * POSTS_PER_USER + round);
    }
}
