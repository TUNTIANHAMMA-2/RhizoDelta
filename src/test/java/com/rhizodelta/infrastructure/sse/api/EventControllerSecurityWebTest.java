package com.rhizodelta.infrastructure.sse.api;

import com.rhizodelta.infrastructure.security.config.SecurityConfig;
import com.rhizodelta.infrastructure.security.filter.JwtAuthenticationFilter;
import com.rhizodelta.infrastructure.sse.service.SseEventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.DispatcherType;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = EventController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
@TestPropertySource(properties = "rhizodelta.jwt.secret=test-jwt-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256-signing")
class EventControllerSecurityWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SseEventService sseEventService;

    @Test
    void asyncDispatcherShouldBypassAuthenticationForSseRedispatch() throws Exception {
        when(sseEventService.register(org.mockito.ArgumentMatchers.any())).thenReturn(new SseEmitter());

        mockMvc.perform(get("/api/events/stream").with(request -> {
                    request.setDispatcherType(DispatcherType.ASYNC);
                    return request;
                }))
                .andExpect(status().isOk());
    }

    @Test
    void errorDispatcherShouldBypassAuthentication() throws Exception {
        mockMvc.perform(get("/error").with(request -> {
                    request.setDispatcherType(DispatcherType.ERROR);
                    return request;
                }))
                .andExpect(result -> {
                    int statusCode = result.getResponse().getStatus();
                    org.assertj.core.api.Assertions.assertThat(statusCode)
                            .isNotEqualTo(401)
                            .isNotEqualTo(403);
                });
    }
}
