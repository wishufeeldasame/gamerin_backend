package com.gamerin.backend.global.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false"
})
@AutoConfigureMockMvc
class SecurityConfigSwaggerDisabledIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiDocsAreDeniedWithoutOAuthRedirectWhenSpringdocIsDisabled() throws Exception {
        mockMvc.perform(get("/v3/api-docs").accept(MediaType.TEXT_HTML))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void apiDocsAreDeniedForAuthenticatedUsersWhenSpringdocIsDisabled() throws Exception {
        mockMvc.perform(get("/v3/api-docs").with(user("tester")))
                .andExpect(status().isForbidden());
    }

    @Test
    void swaggerUiIsDeniedWithoutOAuthRedirectWhenSpringdocIsDisabled() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html").accept(MediaType.TEXT_HTML))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void swaggerUiExactPathIsDeniedWithoutOAuthRedirectWhenSpringdocIsDisabled() throws Exception {
        mockMvc.perform(get("/swagger-ui").accept(MediaType.TEXT_HTML))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void apiDocsYamlIsDeniedWithoutOAuthRedirectWhenSpringdocIsDisabled() throws Exception {
        mockMvc.perform(get("/v3/api-docs.yaml").accept(MediaType.TEXT_HTML))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Location"));
    }
}
