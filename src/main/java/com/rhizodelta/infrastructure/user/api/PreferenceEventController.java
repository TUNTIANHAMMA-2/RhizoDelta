package com.rhizodelta.infrastructure.user.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.infrastructure.security.model.AuthenticatedUser;
import com.rhizodelta.infrastructure.security.model.AuthenticatedUsers;
import com.rhizodelta.infrastructure.user.domain.PreferenceEventType;
import com.rhizodelta.infrastructure.user.service.PreferenceEventService;
import com.rhizodelta.infrastructure.user.service.PrefersAggregationPolicy;
import com.rhizodelta.infrastructure.web.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接收前端交互偏好事件。
 *
 * <p>控制器只负责认证与轻量校验，事件落库仍复用
 * {@link PreferenceEventService}，保持 A1 的聚合输入语义不变。
 */
@RestController
@RequestMapping("/api/users/me/events")
public class PreferenceEventController {
    private static final double WEIGHT_EPSILON = 0.000001;

    private final PreferenceEventService preferenceEventService;
    private final PrefersAggregationPolicy prefersAggregationPolicy;

    public PreferenceEventController(PreferenceEventService preferenceEventService,
                                     PrefersAggregationPolicy prefersAggregationPolicy) {
        this.preferenceEventService = preferenceEventService;
        this.prefersAggregationPolicy = prefersAggregationPolicy;
    }

    @PostMapping
    public ApiResponse<Void> createEvent(
            @RequestBody PreferenceEventRequest request,
            Authentication authentication
    ) {
        AuthenticatedUser user = AuthenticatedUsers.require(authentication);
        PreferenceEventType type = parseType(request.type());
        double weight = prefersAggregationPolicy.baseWeight(type);
        validateOptionalWeight(request.weight(), weight);
        String sourceNodeId = requireText(request.sourceNodeId(), "source_node_id is required");

        preferenceEventService.recordEvent(
                user.sub(),
                blankToNull(request.topicId()),
                type.name(),
                weight,
                sourceNodeId
        );
        return ApiResponse.ok(null);
    }

    private static PreferenceEventType parseType(String rawType) {
        String type = requireText(rawType, "type is required");
        try {
            return PreferenceEventType.valueOf(type);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("type must be one of VIEW, EXPAND, DWELL, LIKE, SHARE", exception);
        }
    }

    private static void validateOptionalWeight(Double weight, double expectedWeight) {
        if (weight == null) {
            return;
        }
        if (!Double.isFinite(weight) || Math.abs(weight - expectedWeight) > WEIGHT_EPSILON) {
            throw new IllegalArgumentException("weight is derived from type and must match server policy");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public record PreferenceEventRequest(
            @JsonProperty("type") String type,
            @JsonProperty("topicId") String topicId,
            @JsonProperty("weight") Double weight,
            @JsonProperty("sourceNodeId") String sourceNodeId
    ) {
    }
}
