package com.gamerin.backend.global.security.config;

import com.gamerin.backend.global.security.jwt.JwtAuthenticationFilter;
import com.gamerin.backend.global.security.oauth2.OAuth2SuccessHandler;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;
    private final List<String> allowedOrigins;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            OAuth2SuccessHandler oAuth2SuccessHandler,
            @Value("${app.cors.allowed-origins:http://localhost:3000}") List<String> allowedOrigins
    ) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.oAuth2SuccessHandler = oAuth2SuccessHandler;
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
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
                                // Swagger 관련 엔드포인트는 인증 없이 접근 허용  배포시 삭제 !!! 
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/swagger-ui.html"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exception -> exception
                        .defaultAuthenticationEntryPointFor(
                                (request, response, authException) ->{
                                        // 1. 상태 코드를 401로 설정
                                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                                        // 2. 응답 타입을 JSON 및 UTF-8 한글 깨짐 방지 설정
                                        response.setContentType("application/json;charset=UTF-8");
                                        // 3. 클라이언트에게 보낼 JSON 에러 메시지 작성
                                        String jsonResponse = "{" +
                                            "\"code\": \"AUTH_UNAUTHORIZED\", " +
                                            "\"message\": \"로그인이 필요하거나 인증이 만료되었습니다.\", " +
                                            "\"data\": null" +
                                            "}";
                                        // 4. 응답 본문에 쓰기
                                        response.getWriter().write(jsonResponse);
                                },
                                new AntPathRequestMatcher("/api/**")
                        )
                )
                .oauth2Login(oauth2 -> oauth2
                            .successHandler(oAuth2SuccessHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
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
