package com.gamerin.backend.global.security.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    @Test
    void buildPermitAllPatternsIncludesSwaggerPathsWhenSpringdocIsEnabled() {
        String[] patterns = SecurityConfig.buildPermitAllPatterns(true, true);

        assertThat(patterns)
                .contains(
                        "/swagger-ui",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/v3/api-docs.yaml"
                )
                .contains("/api/v1/auth/login", "/uploads/**");
    }

    @Test
    void buildPermitAllPatternsExcludesSwaggerPathsWhenSpringdocIsDisabled() {
        String[] patterns = SecurityConfig.buildPermitAllPatterns(false, false);

        assertThat(patterns)
                .doesNotContain(
                        "/swagger-ui",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/v3/api-docs.yaml"
                )
                .contains("/api/v1/auth/login", "/uploads/**");
    }

    @Test
    void buildDenyAllPatternsIncludesSwaggerPathsWhenSpringdocIsDisabled() {
        String[] patterns = SecurityConfig.buildDenyAllPatterns(false, false);

        assertThat(patterns)
                .contains(
                        "/swagger-ui",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/v3/api-docs.yaml"
                );
    }

    @Test
    void buildDenyAllPatternsExcludesSwaggerPathsWhenSpringdocIsEnabled() {
        String[] patterns = SecurityConfig.buildDenyAllPatterns(true, true);

        assertThat(patterns).isEmpty();
    }

    @Test
    void buildPatternsCanDisableSwaggerUiOnly() {
        String[] permitAllPatterns = SecurityConfig.buildPermitAllPatterns(false, true);
        String[] denyAllPatterns = SecurityConfig.buildDenyAllPatterns(false, true);

        assertThat(permitAllPatterns)
                .doesNotContain("/swagger-ui", "/swagger-ui/**", "/swagger-ui.html")
                .contains("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml");
        assertThat(denyAllPatterns)
                .contains("/swagger-ui", "/swagger-ui/**", "/swagger-ui.html")
                .doesNotContain("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml");
    }

    @Test
    void buildPatternsCanDisableApiDocsOnly() {
        String[] permitAllPatterns = SecurityConfig.buildPermitAllPatterns(true, false);
        String[] denyAllPatterns = SecurityConfig.buildDenyAllPatterns(true, false);

        assertThat(permitAllPatterns)
                .contains("/swagger-ui", "/swagger-ui/**", "/swagger-ui.html")
                .doesNotContain("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml");
        assertThat(denyAllPatterns)
                .doesNotContain("/swagger-ui", "/swagger-ui/**", "/swagger-ui.html")
                .contains("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml");
    }
}
