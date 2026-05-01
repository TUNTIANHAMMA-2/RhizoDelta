package com.rhizodelta.infrastructure.user.api;

import com.rhizodelta.infrastructure.security.model.AuthenticatedUsers;
import com.rhizodelta.infrastructure.user.service.FeedService;
import com.rhizodelta.infrastructure.web.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users/me")
public class FeedController {
    private final FeedService feedService;

    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    @GetMapping("/feed")
    public ApiResponse<Map<String, Object>> getFeed(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication authentication
    ) {
        var user = AuthenticatedUsers.require(authentication);
        List<Map<String, Object>> items = feedService.getFeed(user.sub(), page, size);
        return ApiResponse.ok(Map.of(
                "items", items,
                "page", page,
                "size", size,
                "has_next", items.size() >= size
        ));
    }
}
