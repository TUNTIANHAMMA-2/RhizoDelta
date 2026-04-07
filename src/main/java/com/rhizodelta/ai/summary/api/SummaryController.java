package com.rhizodelta.ai.summary.api;

import com.rhizodelta.ai.summary.domain.SummaryResult;
import com.rhizodelta.ai.summary.service.SummaryAgentService;
import com.rhizodelta.infrastructure.web.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/nodes")
public class SummaryController {
    private final SummaryAgentService summaryAgentService;

    public SummaryController(SummaryAgentService summaryAgentService) {
        this.summaryAgentService = summaryAgentService;
    }

    @PostMapping("/{id}/summarize")
    public ApiResponse<SummaryResult> summarize(@PathVariable("id") String id) {
        UUID nodeId = parseUuid(id);
        SummaryResult result = summaryAgentService.generate(nodeId);
        return ApiResponse.ok(result);
    }

    private static UUID parseUuid(String rawId) {
        try {
            return UUID.fromString(rawId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("id must be a valid UUID", exception);
        }
    }
}
