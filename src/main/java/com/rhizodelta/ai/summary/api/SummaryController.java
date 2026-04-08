package com.rhizodelta.ai.summary.api;

import com.rhizodelta.ai.summary.domain.SummaryResult;
import com.rhizodelta.ai.summary.service.SummaryAgentService;
import com.rhizodelta.infrastructure.web.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 暴露节点摘要生成接口。
 *
 * <p>该控制器提供一个显式的摘要触发入口，允许调用方按节点 ID 发起摘要生成流程。
 */
@RestController
@RequestMapping("/api/nodes")
public class SummaryController {
    private final SummaryAgentService summaryAgentService;

    public SummaryController(SummaryAgentService summaryAgentService) {
        this.summaryAgentService = summaryAgentService;
    }

    /**
     * 为指定节点生成摘要。
     *
     * <p>该接口会触发真实的模型调用和摘要写回，而不是只返回预测结果。
     *
     * <p>
     *
     * @param id 节点 UUID 字符串。
     * @return 摘要结果。
     */
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
