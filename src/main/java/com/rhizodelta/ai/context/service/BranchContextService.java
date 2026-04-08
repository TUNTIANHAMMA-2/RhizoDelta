package com.rhizodelta.ai.context.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 负责构建分支上下文并格式化为提示词片段。
 *
 * <p>该服务从图谱中聚合祖先链、已有共识和同级回复，用于为
 * {@code routing} 和 {@code summary} 流程补充上下文语义。
 *
 * <p><b>关键特征</b>：
 * <ul>
 *   <li>只读访问 Neo4j，不写库。</li>
 *   <li>会根据配置限制祖先深度、同级回复数量和最终字符预算。</li>
 *   <li>格式化方法会主动截断超预算内容，而不是静默溢出。</li>
 * </ul>
 */
@Service
public class BranchContextService {
    private static final Logger LOGGER = LoggerFactory.getLogger(BranchContextService.class);

    private static final String ANCESTOR_CHAIN_QUERY = """
            MATCH path = (source:GraphNode {node_id: $sourceNodeId})
                         -[:CONTINUES_FROM|BRANCHED_FROM*1..50]->(ancestor)
            WHERE NOT coalesce(source._deleted, false)
              AND NOT coalesce(ancestor._deleted, false)
              AND length(path) <= $maxDepth
            WITH DISTINCT ancestor, min(length(path)) AS depth
            OPTIONAL MATCH (ai:AI_Consensus)-[:MERGED_INTO]->(ancestor)
              WHERE NOT coalesce(ai._deleted, false)
            RETURN toString(ancestor.node_id) AS nodeId,
                   CASE WHEN 'Human_Post' IN labels(ancestor) THEN 'Human_Post'
                        ELSE 'AI_Consensus' END AS label,
                   coalesce(ai.summary_content, ancestor.content, ancestor.summary_content) AS text,
                   depth
            ORDER BY depth ASC
            """;

    private static final String EXISTING_CONSENSUS_QUERY = """
            MATCH (ai:AI_Consensus)-[:MERGED_INTO]->(source:GraphNode {node_id: $sourceNodeId})
            WHERE NOT coalesce(ai._deleted, false)
            RETURN toString(ai.node_id) AS nodeId,
                   ai.summary_content AS summaryContent
            ORDER BY ai.created_at DESC
            """;

    private static final String SIBLING_REPLIES_QUERY = """
            MATCH (sibling:Human_Post)-[:CONTINUES_FROM]->(source:GraphNode {node_id: $sourceNodeId})
            WHERE NOT coalesce(sibling._deleted, false)
              AND sibling.node_id <> $excludeNodeId
            RETURN toString(sibling.node_id) AS nodeId,
                   sibling.content AS content
            ORDER BY sibling.created_at DESC
            LIMIT $maxSiblings
            """;

    private final Neo4jClient neo4jClient;
    private final int maxAncestorDepth;
    private final int maxSiblingCount;
    private final int maxCharBudget;

    public BranchContextService(
            Neo4jClient neo4jClient,
            @Value("${rhizodelta.ai.context.max-ancestor-depth:5}") int maxAncestorDepth,
            @Value("${rhizodelta.ai.context.max-sibling-count:5}") int maxSiblingCount,
            @Value("${rhizodelta.ai.summary.max-source-tokens:4096}") int maxSourceTokens
    ) {
        this.neo4jClient = neo4jClient;
        this.maxAncestorDepth = maxAncestorDepth;
        this.maxSiblingCount = maxSiblingCount;
        this.maxCharBudget = maxSourceTokens * 4; // rough chars-per-token
    }

    /**
     * 表示单条上下文片段。
     *
     * <p>该对象统一承载节点标识、类型、文本和相对深度，便于后续格式化策略复用。
     */
    public record ContextEntry(String nodeId, String label, String text, int depth) {}

    /**
     * 表示完整的分支上下文集合。
     *
     * <p>该对象将祖先、已有共识和同级回复显式分区，避免后续提示词构建阶段混淆来源。
     */
    public record BranchContext(
            List<ContextEntry> ancestors,
            List<ContextEntry> existingConsensus,
            List<ContextEntry> siblings
    ) {}

    /**
     * 构建指定源节点的分支上下文。
     *
     * <p>该方法会读取三类上下文：
     * <ul>
     *   <li>祖先链，帮助理解当前讨论从何而来。</li>
     *   <li>已有共识，帮助判断是否已有汇总结论。</li>
     *   <li>同级回复，帮助判断新内容是否重复或偏离。</li>
     * </ul>
     *
     * <p>
     *
     * @param sourceNodeId 源节点 ID。
     * @param excludePostNodeId 需要从同级回复中排除的帖子节点。
     * @return 分支上下文。
     */
    public BranchContext buildContext(UUID sourceNodeId, UUID excludePostNodeId) {
        List<ContextEntry> ancestors = queryAncestors(sourceNodeId);
        List<ContextEntry> consensus = queryExistingConsensus(sourceNodeId);
        List<ContextEntry> siblings = querySiblings(sourceNodeId, excludePostNodeId);

        LOGGER.debug("BranchContext built for source={}: ancestors={} consensus={} siblings={}",
                sourceNodeId, ancestors.size(), consensus.size(), siblings.size());
        return new BranchContext(ancestors, consensus, siblings);
    }

    /**
     * 将分支上下文格式化为路由提示词片段。
     *
     * <p>路由场景需要保留更多结构化元数据，因此该格式会包含节点 ID、标签与深度信息。
     *
     * <p>
     *
     * @param ctx 分支上下文。
     * @return 路由场景可直接拼接的文本。
     */
    public String formatForRouting(BranchContext ctx) {
        if (isEmpty(ctx)) return "";

        StringBuilder sb = new StringBuilder();
        int budget = maxCharBudget;

        budget = appendSection(sb, budget, "--- branch ancestors (root → current) ---",
                ctx.ancestors(), true);
        budget = appendSection(sb, budget, "--- existing consensus on source ---",
                ctx.existingConsensus(), true);
        appendSection(sb, budget, "--- sibling replies ---",
                ctx.siblings(), false);

        return sb.toString();
    }

    /**
     * 将分支上下文格式化为摘要提示词片段。
     *
     * <p>摘要场景更关注内容本身，因此会压缩元数据，只保留必要的文本和轻量前缀。
     *
     * <p>
     *
     * @param ctx 分支上下文。
     * @return 摘要场景可直接拼接的文本。
     */
    public String formatForSummary(BranchContext ctx) {
        if (isEmpty(ctx)) return "";

        StringBuilder sb = new StringBuilder();
        int budget = maxCharBudget;

        // Compact format: just content, no metadata
        for (ContextEntry entry : ctx.ancestors()) {
            String line = entry.text() == null ? "" : entry.text().trim();
            if (line.isEmpty()) continue;
            String block = line + "\n";
            if (sb.length() + block.length() > budget) {
                sb.append("[... truncated]\n");
                break;
            }
            sb.append(block);
            budget -= block.length();
        }

        for (ContextEntry entry : ctx.existingConsensus()) {
            String line = entry.text() == null ? "" : entry.text().trim();
            if (line.isEmpty()) continue;
            String block = "[Consensus] " + line + "\n";
            if (sb.length() + block.length() > budget) {
                sb.append("[... truncated]\n");
                break;
            }
            sb.append(block);
            budget -= block.length();
        }

        for (ContextEntry entry : ctx.siblings()) {
            String line = entry.text() == null ? "" : entry.text().trim();
            if (line.isEmpty()) continue;
            String block = "[Sibling] " + line + "\n";
            if (sb.length() + block.length() > budget) {
                sb.append("[... truncated]\n");
                break;
            }
            sb.append(block);
            budget -= block.length();
        }

        return sb.toString();
    }

    private List<ContextEntry> queryAncestors(UUID sourceNodeId) {
        Collection<ContextEntry> results = neo4jClient.query(ANCESTOR_CHAIN_QUERY)
                .bind(sourceNodeId.toString()).to("sourceNodeId")
                .bind(maxAncestorDepth).to("maxDepth")
                .fetchAs(ContextEntry.class)
                .mappedBy((ts, record) -> new ContextEntry(
                        record.get("nodeId").asString(null),
                        record.get("label").asString("Unknown"),
                        record.get("text").asString(null),
                        record.get("depth").asInt(0)
                ))
                .all();
        return new ArrayList<>(results);
    }

    private List<ContextEntry> queryExistingConsensus(UUID sourceNodeId) {
        Collection<ContextEntry> results = neo4jClient.query(EXISTING_CONSENSUS_QUERY)
                .bind(sourceNodeId.toString()).to("sourceNodeId")
                .fetchAs(ContextEntry.class)
                .mappedBy((ts, record) -> new ContextEntry(
                        record.get("nodeId").asString(null),
                        "AI_Consensus",
                        record.get("summaryContent").asString(null),
                        0
                ))
                .all();
        return new ArrayList<>(results);
    }

    private List<ContextEntry> querySiblings(UUID sourceNodeId, UUID excludePostNodeId) {
        Collection<ContextEntry> results = neo4jClient.query(SIBLING_REPLIES_QUERY)
                .bind(sourceNodeId.toString()).to("sourceNodeId")
                .bind(excludePostNodeId.toString()).to("excludeNodeId")
                .bind(maxSiblingCount).to("maxSiblings")
                .fetchAs(ContextEntry.class)
                .mappedBy((ts, record) -> new ContextEntry(
                        record.get("nodeId").asString(null),
                        "Human_Post",
                        record.get("content").asString(null),
                        0
                ))
                .all();
        return new ArrayList<>(results);
    }

    private int appendSection(StringBuilder sb, int budget, String header,
                              List<ContextEntry> entries, boolean includeMetadata) {
        if (entries.isEmpty()) return budget;
        String headerLine = header + "\n";
        if (budget <= headerLine.length()) {
            sb.append("[... truncated]\n");
            return 0;
        }
        sb.append(headerLine);
        budget -= headerLine.length();

        for (ContextEntry entry : entries) {
            String text = entry.text() == null ? "" : entry.text().trim();
            if (text.isEmpty()) continue;
            String line;
            if (includeMetadata) {
                line = "node_id=%s label=%s depth=%d content=%s\n".formatted(
                        entry.nodeId(), entry.label(), entry.depth(),
                        text.replace('\n', ' '));
            } else {
                line = "node_id=%s content=%s\n".formatted(
                        entry.nodeId(), text.replace('\n', ' '));
            }
            if (budget <= line.length()) {
                sb.append("[... truncated]\n");
                return 0;
            }
            sb.append(line);
            budget -= line.length();
        }
        return budget;
    }

    private boolean isEmpty(BranchContext ctx) {
        return ctx.ancestors().isEmpty() && ctx.existingConsensus().isEmpty() && ctx.siblings().isEmpty();
    }
}
