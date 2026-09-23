package com.example.gym;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gym.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.login-per-minute=3",
        "app.rate-limit.retry-after-seconds=30"
})
class RateLimitIT extends AbstractIntegrationTest {

    @Test
    void loginRateLimitReturns429WithRetryAfter() throws Exception {
        String body = """
                {"usernameOrEmail":"missing","password":"wrong"}
                """;
        String clientIp = "203.0.113." + (50 + (int) (System.nanoTime() % 100));
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .header("X-Forwarded-For", clientIp)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Forwarded-For", clientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.correlationId").exists());
    }
}
