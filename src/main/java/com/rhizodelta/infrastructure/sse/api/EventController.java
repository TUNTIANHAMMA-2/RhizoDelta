package com.rhizodelta.infrastructure.sse.api;

import com.rhizodelta.infrastructure.sse.service.SseEventService;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 提供 SSE 事件流订阅入口。
 *
 * <p>该控制器负责为当前请求注册一个新的事件流连接，
 * 使前端可以持续接收节点创建、决策完成和编排状态等事件。
 */
@RestController
@RequestMapping("/api/events")
public class EventController {
    private final SseEventService sseEventService;

    public EventController(SseEventService sseEventService) {
        this.sseEventService = sseEventService;
    }

    /**
     * 注册一个新的 SSE 连接。
     *
     * <p>若当前请求已经过认证，会把用户 ID 绑定到 emitter 上，用于后续按作者过滤编排状态事件。
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Authentication authentication) {
        String userId = authentication != null ? authentication.getName() : null;
        return sseEventService.register(userId);
    }
}
