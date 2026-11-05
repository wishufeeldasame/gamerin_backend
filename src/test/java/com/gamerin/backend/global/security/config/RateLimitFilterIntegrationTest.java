package com.gamerin.backend.global.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RateLimitFilterIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void findIdIsLimitedPerRemoteAddressEvenWhenXForwardedForChanges() throws Exception {
        // 다른 테스트와 카운터가 섞이지 않도록 전용 원격 주소를 쓴다 (빈 email은 검증 실패라 DB를 거치지 않는다)
        for (int i = 0; i < 10; i++) {
            int status = mockMvc.perform(findId("198.51.100." + i)).andReturn().getResponse().getStatus();
            assertThat(status).isNotEqualTo(429);
        }

        mockMvc.perform(findId("198.51.100.99"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false));
    }

    private MockHttpServletRequestBuilder findId(String xForwardedFor) {
        return post("/api/v1/auth/find-id")
                .with(request -> {
                    request.setRemoteAddr("203.0.113.136");
                    return request;
                })
                .header("X-Forwarded-For", xForwardedFor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"\"}");
    }
}
