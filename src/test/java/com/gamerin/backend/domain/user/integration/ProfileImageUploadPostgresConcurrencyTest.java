package com.gamerin.backend.domain.user.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.gamerin.backend.domain.user.dto.request.ProfileImageTarget;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.user.service.UserService;

@Tag("postgresql")
@EnabledIfEnvironmentVariable(named = "HASHTAG_POSTGRES_TEST_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProfileImageUploadPostgresConcurrencyTest {

    private static final int CONCURRENT_UPLOADS = 4;
    private static PostgresSchema postgresSchema;
    private static Path uploadRoot;

    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws IOException {
        postgresSchema = PostgresSchema.create();
        uploadRoot = Files.createTempDirectory("profile-image-upload-test");
        registry.add("spring.datasource.url", postgresSchema::url);
        registry.add("spring.datasource.username", postgresSchema::username);
        registry.add("spring.datasource.password", postgresSchema::password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.hikari.schema", postgresSchema::schema);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.default_schema", postgresSchema::schema);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", postgresSchema::schema);
        registry.add("spring.flyway.default-schema", postgresSchema::schema);
        registry.add("spring.flyway.create-schemas", () -> "false");
        registry.add("spring.flyway.baseline-on-migrate", () -> "false");
        registry.add("app.moderation.enabled", () -> "false");
        registry.add("app.media.upload-dir", () -> uploadRoot.toString());
    }

    @AfterAll
    static void cleanUp() throws IOException {
        if (postgresSchema != null) {
            postgresSchema.close();
        }
        if (uploadRoot != null) {
            try (Stream<Path> paths = Files.walk(uploadRoot)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    /**
     * #132: 동시 프로필 이미지 업로드가 같은 이전 URL을 읽어도 파일이 고아로 남지 않고 DB URL의 파일 하나만 남는다.
     */
    @Test
    void concurrentProfileImageUploadsLeaveOnlyTheFileReferencedByDb() throws Exception {
        User user = transactionTemplate.execute(status -> {
            String handle = "imgup" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            User created = User.createLocal(handle + "@example.com", handle, "Uploader", "encoded-password");
            created.setProfile(UserProfile.createDefault(created));
            return userRepository.saveAndFlush(created);
        });
        UUID userId = user.getId();
        byte[] jpeg = jpegBytes();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_UPLOADS);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_UPLOADS; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return userService.uploadProfileImage(userId, ProfileImageTarget.PROFILE,
                            new MockMultipartFile("file", "avatar.jpg", "image/jpeg", jpeg));
                }));
            }
            start.countDown();
            for (Future<Object> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        String dbUrl = jdbcTemplate.queryForObject(
                "select profile_image_url from user_profiles where user_id = ?", String.class, userId);
        Path profileDir = uploadRoot.resolve("profile-images").resolve(userId.toString()).resolve("profile");
        List<String> files;
        try (Stream<Path> paths = Files.list(profileDir)) {
            files = paths.map(path -> path.getFileName().toString()).toList();
        }

        assertThat(files).hasSize(1);
        assertThat(dbUrl).endsWith("/" + files.get(0));
    }

    private static byte[] jpegBytes() throws IOException {
        BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private record PostgresSchema(String url, String username, String password, String schema) {

        private static PostgresSchema create() {
            String url = requiredEnvironment("HASHTAG_POSTGRES_TEST_URL", false);
            String username = requiredEnvironment("HASHTAG_POSTGRES_TEST_USERNAME", false);
            String password = requiredEnvironment("HASHTAG_POSTGRES_TEST_PASSWORD", true);
            String schema = "profile_image_concurrency_" + UUID.randomUUID().toString().replace("-", "");

            executeSchemaStatement(url, username, password, "create schema " + schema);
            return new PostgresSchema(url, username, password, schema);
        }

        private void close() {
            executeSchemaStatement(url, username, password, "drop schema if exists " + schema + " cascade");
        }

        private static String requiredEnvironment(String name, boolean emptyAllowed) {
            String value = System.getenv(name);
            if (value == null || (!emptyAllowed && value.isBlank())) {
                throw new IllegalStateException(name + " must be set for the PostgreSQL profile image test.");
            }
            return value;
        }

        private static void executeSchemaStatement(String url, String username, String password, String sql) {
            try (Connection connection = DriverManager.getConnection(url, username, password);
                    Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException error) {
                throw new IllegalStateException("Could not prepare the PostgreSQL profile image test schema.", error);
            }
        }
    }
}
