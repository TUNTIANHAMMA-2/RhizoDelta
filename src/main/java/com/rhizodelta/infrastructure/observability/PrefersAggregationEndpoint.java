package com.rhizodelta.infrastructure.observability;

import com.rhizodelta.infrastructure.user.service.PrefersAggregationJob;
import com.rhizodelta.infrastructure.user.service.PrefersAggregationOutcome;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自定义 actuator 端点：POST {@code /actuator/prefers-aggregation} 立刻补跑一轮 PREFERS 聚合。
 *
 * <p>动机：聚合 Job 默认每 5 分钟一次；冷启动、异常恢复或运营临时需求场景下，操作员需要
 * 一个不重启进程、不直连数据库的"立刻补跑"通道。直接暴露 {@link PrefersAggregationJob#runOnce()}
 * 同时让 {@link PrefersAggregationOutcome} 的终态摘要可观察。
 *
 * <p>鉴权由 {@code SecurityConfig} 限定为 {@code ROLE_ADMIN}；本端点本身不重复校验。
 *
 * <p>响应是扁平 JSON：
 * <pre>{@code
 * {
 *   "status": "OK" | "SKIPPED" | "ERROR",
 *   "invoked_at": "2026-05-17T08:32:15Z",
 *   "result": { "events_processed": 142, "edges_upserted": 38, "window_start": "..." },
 *   "error_message": ""
 * }
 * }</pre>
 * 当 {@code status != OK} 时 {@code result} 为空 map；当 {@code status != ERROR} 时
 * {@code error_message} 为空字符串。这样字段总是存在，前端 / jq 解析不需要做存在性判断。
 */
@Component
@Endpoint(id = "prefers-aggregation")
public class PrefersAggregationEndpoint {

    private final PrefersAggregationJob job;

    public PrefersAggregationEndpoint(PrefersAggregationJob job) {
        this.job = job;
    }

    @WriteOperation
    public Map<String, Object> replay() {
        PrefersAggregationOutcome outcome = job.runOnce();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", outcome.status().name());
        body.put("invoked_at", outcome.invokedAt().toString());
        body.put("result", outcome.result() == null
                ? Map.of()
                : Map.of(
                        "events_processed", outcome.result().eventsProcessed(),
                        "edges_upserted", outcome.result().edgesUpserted(),
                        "window_start", outcome.result().windowStart().toString()
                ));
        body.put("error_message", outcome.errorMessage() == null ? "" : outcome.errorMessage());
        return body;
    }
}
