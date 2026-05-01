package com.rhizodelta.infrastructure.user.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rhizodelta.infrastructure.security.model.AuthenticatedUsers;
import com.rhizodelta.infrastructure.user.service.MuteService;
import com.rhizodelta.infrastructure.web.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users/me")
public class MuteController {
    private final MuteService muteService;

    public MuteController(MuteService muteService) {
        this.muteService = muteService;
    }

    @PostMapping("/mutes")
    public ApiResponse<Map<String, Object>> mute(
            @RequestBody MuteRequest request,
            Authentication authentication
    ) {
        var user = AuthenticatedUsers.require(authentication);
        return ApiResponse.ok(muteService.mute(user.sub(), request.targetType(), request.targetId(), request.reason()));
    }

    @GetMapping("/mutes")
    public ApiResponse<Map<String, Object>> listMutes(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication
    ) {
        var user = AuthenticatedUsers.require(authentication);
        return ApiResponse.ok(muteService.listMutes(user.sub(), page, size));
    }

    @DeleteMapping("/mutes/{muteId}")
    public ApiResponse<Void> unmute(
            @PathVariable("muteId") String muteId,
            Authentication authentication
    ) {
        var user = AuthenticatedUsers.require(authentication);
        muteService.unmute(user.sub(), muteId);
        return ApiResponse.ok(null);
    }

    public record MuteRequest(
            @JsonProperty("target_type") String targetType,
            @JsonProperty("target_id") String targetId,
            @JsonProperty("reason") String reason
    ) {}
}
