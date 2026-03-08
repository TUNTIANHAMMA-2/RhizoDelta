package com.rhizodelta.service;

import com.rhizodelta.domain.node.AIConsensus;
import com.rhizodelta.domain.node.HumanPost;
import com.rhizodelta.repository.AIConsensusRepository;
import com.rhizodelta.repository.HumanPostRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

@Service
public class NodeQueryService {
    private static final int DEFAULT_MAX_DEPTH = 10;
    private static final int MAX_ALLOWED_DEPTH = 50;

    private final HumanPostRepository humanPostRepository;
    private final AIConsensusRepository aiConsensusRepository;

    public NodeQueryService(HumanPostRepository humanPostRepository, AIConsensusRepository aiConsensusRepository) {
        this.humanPostRepository = humanPostRepository;
        this.aiConsensusRepository = aiConsensusRepository;
    }

    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public NodeResult getNodeById(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");

        return humanPostRepository.findByNodeId(nodeId)
                .<NodeResult>map(HumanPostNode::new)
                .or(() -> aiConsensusRepository.findByNodeId(nodeId).map(AIConsensusNode::new))
                .orElseThrow(() -> new NoSuchElementException("Node not found: " + nodeId));
    }

    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public List<HumanPost> getLineage(UUID nodeId, Integer maxDepth) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        int resolvedMaxDepth = resolveMaxDepth(maxDepth);
        return humanPostRepository.findLineage(nodeId, resolvedMaxDepth);
    }

    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public List<HumanPost> getProvenance(UUID consensusNodeId) {
        Objects.requireNonNull(consensusNodeId, "consensusNodeId must not be null");

        aiConsensusRepository.findByNodeId(consensusNodeId)
                .orElseThrow(() -> new NoSuchElementException("AI_Consensus not found: " + consensusNodeId));

        return humanPostRepository.findProvenance(consensusNodeId);
    }

    private int resolveMaxDepth(Integer maxDepth) {
        if (maxDepth == null) {
            return DEFAULT_MAX_DEPTH;
        }
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("maxDepth must be greater than 0");
        }
        return Math.min(maxDepth, MAX_ALLOWED_DEPTH);
    }

    public sealed interface NodeResult permits HumanPostNode, AIConsensusNode {
    }

    public record HumanPostNode(HumanPost node) implements NodeResult {
    }

    public record AIConsensusNode(AIConsensus node) implements NodeResult {
    }
}
