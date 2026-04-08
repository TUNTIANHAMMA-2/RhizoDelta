package com.rhizodelta.core.service;

import com.rhizodelta.core.validation.DecisionCommandValidation;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;

/**
 * 解析任意图节点所属的根节点标识。
 *
 * <p>该服务存在的意义，是把 {@code root_id} 的读取规则收敛为一个可复用入口，
 * 供查询层、AI 召回层和其他需要按根分组的流程共享。
 *
 * <p><b>关键副作用</b>：
 * <ul>
 *   <li>只读访问 Neo4j，不会写库。</li>
 *   <li>当节点不存在或已不可见时，会抛出 {@link NoSuchElementException}。</li>
 * </ul>
 */
@Service
public class GraphRootLocatorService {
    private static final String ROOT_ID_QUERY = """
            MATCH (node:GraphNode {node_id: $nodeId})
            WHERE NOT coalesce(node._deleted, false)
            RETURN coalesce(node.root_id, node.node_id) AS rootId
            """;

    private final Neo4jClient neo4jClient;

    public GraphRootLocatorService(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    /**
     * 返回指定节点所属谱系的根节点 ID。
     *
     * <p>这里优先读取持久化的 {@code root_id}，若节点尚未显式设置，则回退为节点自身 ID。
     * 这样上层调用方无需了解图谱演化过程中根节点字段的补写细节。
     *
     * <p>
     *
     * @param nodeId 需要解析根节点的图节点 ID。
     * @return 根节点 ID。
     */
    public String resolveRootId(String nodeId) {
        String validatedNodeId = DecisionCommandValidation.requireText(nodeId, "node_id");
        return neo4jClient.query(ROOT_ID_QUERY)
                .bind(validatedNodeId).to("nodeId")
                .fetchAs(String.class)
                .one()
                .orElseThrow(() -> new NoSuchElementException("root_id not found for node_id: " + validatedNodeId));
    }
}
