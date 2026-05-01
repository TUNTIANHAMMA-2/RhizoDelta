package com.rhizodelta.infrastructure.user.api;

import com.rhizodelta.infrastructure.security.model.AuthenticatedUsers;
import com.rhizodelta.infrastructure.user.service.OnlineStatusService;
import com.rhizodelta.infrastructure.web.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class OnlineStatusController {
    private final OnlineStatusService onlineStatusService;

    public OnlineStatusController(OnlineStatusService onlineStatusService) {
        this.onlineStatusService = onlineStatusService;
    }

    @GetMapping("/me/status")
    public ApiResponse<Map<String, Object>> myStatus(Authentication authentication) {
        var user = AuthenticatedUsers.require(authentication);
        return ApiResponse.ok(onlineStatusService.getStatus(user.sub()));
    }

    @GetMapping("/status")
    public ApiResponse<List<Map<String, Object>>> batchStatus(
            @RequestParam("user_ids") String userIdsParam,
            Authentication authentication
    ) {
        AuthenticatedUsers.require(authentication);
        List<String> userIds = Arrays.asList(userIdsParam.split(","));
        return ApiResponse.ok(onlineStatusService.getBatchStatus(userIds));
    }
}
