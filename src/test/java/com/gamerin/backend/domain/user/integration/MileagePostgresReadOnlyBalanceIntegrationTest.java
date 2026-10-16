package com.gamerin.backend.domain.user.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.gamerin.backend.domain.user.dto.response.MyMileageResponse;
import com.gamerin.backend.domain.user.entity.User;
import com.gamerin.backend.domain.user.entity.UserProfile;
import com.gamerin.backend.domain.user.repository.MileageWalletRepository;
import com.gamerin.backend.domain.user.repository.UserRepository;
import com.gamerin.backend.domain.user.service.MileageService;

/**
 * [이슈 #77] 지갑 없는 유저의 마일리지 잔액 조회가 읽기 전용 트랜잭션에서
 * FOR UPDATE나 INSERT 없이 PostgreSQL 환경에서 정상적으로 0원을 반환하는지 검증하는 통합 테스트.
 */
@Tag("postgresql")
@EnabledIfEnvironmentVariable(named = "MILEAGE_BALANCE_POSTGRES_TEST_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MileagePostgresReadOnlyBalanceIntegrationTest {

    private static PostgresSchema postgresSchema;

    @Autowired
    private MileageService mileageService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MileageWalletRepository walletRepository;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        postgresSchema = PostgresSchema.create();

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
    }

    @AfterAll
    static void tearDownPostgres() {
        if (postgresSchema != null) {
            postgresSchema.drop();
        }
    }

    @Test
    @DisplayName("PostgreSQL 환경에서 지갑이 없는 유저가 읽기 전용 트랜잭션으로 마일리지 잔액 조회 시 500 오류 없이 0원을 반환한다")
    void getMyBalance_inPostgres_whenNoWallet_success() {
        // 1. 지갑이 없는 신규 사용자 생성
        User newUser = User.createLocal("nowallet@test.com", "user_" + UUID.randomUUID().toString().substring(0, 8),
                "지갑없는유저", "hash");
        newUser.setProfile(UserProfile.createDefault(newUser));
        newUser = userRepository.save(newUser);

        // 지갑이 실제로 없는 상태인지 확인
        assertThat(walletRepository.findById(newUser.getId())).isEmpty();

        // 2. 읽기 전용 트랜잭션(MileageService.getMyBalance) 호출
        // 수정 전에는 PostgreSQL에서 'cannot execute SELECT FOR NO KEY UPDATE in a read-only
        // transaction' (500) 발생
        MyMileageResponse response = mileageService.getMyBalance(newUser);

        // 3. 검증
        assertThat(response).isNotNull();
        assertThat(response.currentBalance()).isEqualTo(0L);

        // 조회 후에도 지갑이 생성되지 않고 깨끗하게 유지되는지 확인
        assertThat(walletRepository.findById(newUser.getId())).isEmpty();
    }

    private record PostgresSchema(String url, String username, String password, String schema) {
        static PostgresSchema create() {
            String baseUrl = System.getenv("MILEAGE_BALANCE_POSTGRES_TEST_URL");
            String username = System.getenv().getOrDefault("MILEAGE_BALANCE_POSTGRES_TEST_USERNAME", "postgres");
            String password = System.getenv().getOrDefault("MILEAGE_BALANCE_POSTGRES_TEST_PASSWORD", "postgres");
            String schema = "test_mileage_" + UUID.randomUUID().toString().replace("-", "");

            try (Connection conn = DriverManager.getConnection(baseUrl, username, password);
                    Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE SCHEMA " + schema);
            } catch (SQLException e) {
                throw new IllegalStateException("PostgreSQL 테스트 스키마 생성 실패", e);
            }

            return new PostgresSchema(baseUrl, username, password, schema);
        }

        void drop() {
            try (Connection conn = DriverManager.getConnection(url, username, password);
                    Statement stmt = conn.createStatement()) {
                stmt.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            } catch (SQLException ignored) {
            }
        }
    }
}
