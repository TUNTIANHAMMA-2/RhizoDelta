package com.rhizodelta.infrastructure.user.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.infrastructure.security.model.AuthenticatedUsers;
import com.rhizodelta.infrastructure.user.service.FollowService;
import com.rhizodelta.infrastructure.web.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 关注关系 CRUD。
 *
 * <p>资源风格：每条 FOLLOWS 边都带有服务端生成的 {@code follow_id}，
 * 删除路径 {@code DELETE /api/users/me/follows/{follow_id}} 通过该 id 寻址。
 * 这样既符合 RESTful 习惯，也为后续在边上扩展通知/分组属性留出空间。
 */
@RestController
@RequestMapping("/api/users/me")
public class FollowController {
    private final FollowService followService;

    public FollowController(FollowService followService) {
        this.followService = followService;
    }

    @PostMapping("/follows")
    public ApiResponse<Map<String, Object>> follow(
            @RequestBody FollowRequest request,
            Authentication authentication
    ) {
        var user = AuthenticatedUsers.require(authentication);
        return ApiResponse.ok(followService.follow(user.sub(), request.targetType(), request.targetId()));
    }

    @GetMapping("/follows")
    public ApiResponse<Map<String, Object>> listFollows(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication
    ) {
        var user = AuthenticatedUsers.require(authentication);
        return ApiResponse.ok(followService.listFollows(user.sub(), page, size));
    }

    @DeleteMapping("/follows/{followId}")
    public ApiResponse<Void> unfollow(
            @PathVariable("followId") String followId,
            Authentication authentication
    ) {
        var user = AuthenticatedUsers.require(authentication);
        followService.unfollow(user.sub(), followId);
        return ApiResponse.ok(null);
    }

    public record FollowRequest(
            @JsonProperty("target_type") String targetType,
            @JsonProperty("target_id") String targetId
    ) {}
}
