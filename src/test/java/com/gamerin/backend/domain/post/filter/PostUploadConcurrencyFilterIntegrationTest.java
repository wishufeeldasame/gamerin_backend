package com.gamerin.backend.domain.post.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.Semaphore;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "app.media.upload.max-concurrency=1",
        "app.media.upload.acquire-timeout-ms=0"
})
@AutoConfigureMockMvc
class PostUploadConcurrencyFilterIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PostUploadConcurrencyFilter filter;

    @Test
    void authenticatedMultipartPostReturnsTooManyRequestsWhenSlotIsBusy() throws Exception {
        Semaphore uploadSlots = (Semaphore) ReflectionTestUtils.getField(filter, "uploadSlots");
        assertThat(uploadSlots).isNotNull();
        assertThat(uploadSlots.tryAcquire()).isTrue();
        MockMultipartFile video = new MockMultipartFile(
                "mediaFiles",
                "clip.mp4",
                "video/mp4",
                "video".getBytes()
        );

        try {
            mockMvc.perform(multipart("/api/v1/posts")
                            .file(video)
                            .with(user("upload-tester")))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message")
                            .value("현재 업로드 요청이 많습니다. 잠시 후 다시 시도해주세요."))
                    .andExpect(jsonPath("$.data").doesNotExist());
        } finally {
            uploadSlots.release();
        }
    }
}
