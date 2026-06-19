package com.gamerin.backend.global.security.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamerin.backend.domain.user.service.CustomUserDetailsService;
import com.gamerin.backend.global.logging.ApiRequestLoggingFilter;
import com.gamerin.backend.global.logging.JsonLogContext;
import com.gamerin.backend.global.security.jwt.JwtAuthenticationFilter;
import com.gamerin.backend.global.security.jwt.JwtTokenProvider;
import com.gamerin.backend.global.security.oauth2.OAuth2SuccessHandler;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATTERNS = {
            "/api/v1/auth/signup",
            "/api/v1/auth/social-signup",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/auth/find-id",
            "/api/v1/auth/find-password",
            "/api/v1/auth/reset-password",
            "/api/v1/auth/availability/**",
            "/oauth2/**",
            "/login/oauth2/**",
            "/uploads/**"
    };
    private static final String[] SWAGGER_UI_PATTERNS = {
            "/swagger-ui",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };
    private static final String[] API_DOCS_PATTERNS = {
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/v3/api-docs.yaml"
    };
    private static final String[] PRIVATE_UPLOAD_PATTERNS = {
            "/uploads/message-attachments/**"
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtTokenProvider jwtTokenProvider;
    private final CustomUserDetailsService customUserDetailsService;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;
    private final ObjectMapper objectMapper;
    private final List<String> allowedOrigins;
    private final boolean swaggerUiEnabled;
    private final boolean apiDocsEnabled;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            JwtTokenProvider jwtTokenProvider,
            CustomUserDetailsService customUserDetailsService,
            OAuth2SuccessHandler oAuth2SuccessHandler,
            ObjectMapper objectMapper,
            @Value("${app.cors.allowed-origins:http://localhost:3000}") List<String> allowedOrigins,
            @Value("${springdoc.swagger-ui.enabled:true}") boolean swaggerUiEnabled,
            @Value("${springdoc.api-docs.enabled:true}") boolean apiDocsEnabled) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.jwtTokenProvider = jwtTokenProvider;
        this.customUserDetailsService = customUserDetailsService;
        this.oAuth2SuccessHandler = oAuth2SuccessHandler;
        this.objectMapper = objectMapper;
        this.allowedOrigins = allowedOrigins;
        this.swaggerUiEnabled = swaggerUiEnabled;
        this.apiDocsEnabled = apiDocsEnabled;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    String[] denyAllPatterns = buildDenyAllPatterns(swaggerUiEnabled, apiDocsEnabled);
                    if (denyAllPatterns.length > 0) {
                        auth.requestMatchers(denyAllPatterns).denyAll();
                    }
                    auth.requestMatchers(buildPermitAllPatterns(swaggerUiEnabled, apiDocsEnabled)).permitAll()
                            .anyRequest().authenticated();
                })
                .exceptionHandling(exception -> exception
                        .defaultAuthenticationEntryPointFor(
                                (request, response, authException) -> response
                                        .sendError(HttpServletResponse.SC_FORBIDDEN),
                                swaggerRequestMatcher())
                        .defaultAuthenticationEntryPointFor(
                                (request, response, authException) -> {
                                    String message = "Authentication is required or the token has expired.";
                                    JsonLogContext.setFailureReason(request, message);
                                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                                    response.setContentType("application/json;charset=UTF-8");
                                    Map<String, Object> errorResponse = Map.of(
                                            "success", false,
                                            "message", "인증이 필요하거나 토큰이 만료되었습니다."

                                );

                                    response.getWriter().write(objectMapper.writeValueAsString(errorResponse));

                                },
                                new AntPathRequestMatcher("/api/**"))
                        .defaultAuthenticationEntryPointFor(
                                (request, response, authException) -> response
                                        .sendError(HttpServletResponse.SC_UNAUTHORIZED),
                                anyRequestMatcher()))
                .oauth2Login(oauth2 -> oauth2.successHandler(oAuth2SuccessHandler))
                .addFilterBefore(new RateLimitFilter(objectMapper), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(
                        new PrivateUploadStaticPathDenyFilter(jwtTokenProvider, customUserDetailsService),
                        JwtAuthenticationFilter.class)
                .addFilterAfter(new ApiRequestLoggingFilter(), PrivateUploadStaticPathDenyFilter.class);

        return http.build();
    }

    static String[] buildPermitAllPatterns(boolean swaggerUiEnabled, boolean apiDocsEnabled) {
        List<String> patterns = new ArrayList<>(List.of(PUBLIC_PATTERNS));

        if (swaggerUiEnabled) {
            addPatterns(patterns, SWAGGER_UI_PATTERNS);
        }

        if (apiDocsEnabled) {
            addPatterns(patterns, API_DOCS_PATTERNS);
        }

        return patterns.toArray(String[]::new);
    }

    static String[] buildDenyAllPatterns(boolean swaggerUiEnabled, boolean apiDocsEnabled) {
        List<String> patterns = new ArrayList<>(List.of(PRIVATE_UPLOAD_PATTERNS));

        if (!swaggerUiEnabled) {
            addPatterns(patterns, SWAGGER_UI_PATTERNS);
        }

        if (!apiDocsEnabled) {
            addPatterns(patterns, API_DOCS_PATTERNS);
        }

        return patterns.toArray(String[]::new);
    }

    private static void addPatterns(List<String> patterns, String[] additionalPatterns) {
        patterns.addAll(List.of(additionalPatterns));
    }

    private static RequestMatcher swaggerRequestMatcher() {
        return request -> {
            String path = getRequestPath(request);
            return matchesAnyPattern(path, SWAGGER_UI_PATTERNS) || matchesAnyPattern(path, API_DOCS_PATTERNS);
        };
    }

    private static RequestMatcher anyRequestMatcher() {
        return request -> true;
    }

    private static String getRequestPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            return path.substring(contextPath.length());
        }
        return path;
    }

    private static boolean matchesAnyPattern(String path, String[] patterns) {
        for (String pattern : patterns) {
            if (matchesPattern(path, pattern)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesPattern(String path, String pattern) {
        if (pattern.endsWith("/**")) {
            String basePath = pattern.substring(0, pattern.length() - 3);
            return path.equals(basePath) || path.startsWith(basePath + "/");
        }
        return path.equals(pattern);
    }

    private static class PrivateUploadStaticPathDenyFilter extends OncePerRequestFilter {

        private final JwtTokenProvider jwtTokenProvider;
        private final CustomUserDetailsService customUserDetailsService;

        private PrivateUploadStaticPathDenyFilter(
                JwtTokenProvider jwtTokenProvider,
                CustomUserDetailsService customUserDetailsService) {
            this.jwtTokenProvider = jwtTokenProvider;
            this.customUserDetailsService = customUserDetailsService;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            if (!matchesAnyPattern(getRequestPath(request), PRIVATE_UPLOAD_PATTERNS)) {
                filterChain.doFilter(request, response);
                return;
            }

            Authentication authentication = org.springframework.security.core.context.SecurityContextHolder
                    .getContext()
                    .getAuthentication();
            int status = isAuthenticated(authentication) || hasValidBearerToken(request)
                    ? HttpServletResponse.SC_FORBIDDEN
                    : HttpServletResponse.SC_UNAUTHORIZED;
            response.setStatus(status);
            response.flushBuffer();
        }

        private boolean isAuthenticated(Authentication authentication) {
            return authentication != null
                    && authentication.isAuthenticated()
                    && !(authentication instanceof AnonymousAuthenticationToken);
        }

        private boolean hasValidBearerToken(HttpServletRequest request) {
            String bearerToken = request.getHeader("Authorization");
            if (bearerToken == null || !bearerToken.startsWith("Bearer ")) {
                return false;
            }

            String token = bearerToken.substring(7);
            if (token.isBlank() || !jwtTokenProvider.validate(token)) {
                return false;
            }

            try {
                customUserDetailsService.loadById(jwtTokenProvider.getUserId(token));
                return true;
            } catch (UsernameNotFoundException ignored) {
                return false;
            }
        }
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setExposedHeaders(List.of("Set-Cookie"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
