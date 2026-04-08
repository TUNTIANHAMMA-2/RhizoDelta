package com.rhizodelta.ai.routing.service;

import com.rhizodelta.ai.routing.domain.PreFilterResult;
import com.rhizodelta.infrastructure.config.ModelRouterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 负责在进入 LLM 前做规则级快速筛选。
 *
 * <p>该服务根据候选存在性和最高相似度阈值，决定是直接自动分支、自动合并，
 * 还是继续进入 LLM 评估阶段。
 */
@Service
public class RuleBasedPreFilterService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuleBasedPreFilterService.class);

    private final double autoMergeThreshold;
    private final double autoBranchThreshold;

    public RuleBasedPreFilterService(ModelRouterConfig config) {
        this.autoMergeThreshold = config.rules().autoMergeThreshold();
        this.autoBranchThreshold = config.rules().autoBranchThreshold();
        LOGGER.info("RuleBasedPreFilterService initialized auto_merge_threshold={} auto_branch_threshold={}",
                autoMergeThreshold, autoBranchThreshold);
    }

    /**
     * 根据召回结果判断是否跳过 LLM。
     *
     * <p>该方法是一个纯规则决策入口，不访问数据库，也不调用模型。
     *
     * <p>
     *
     * @param topScore 当前最高候选得分。
     * @param hasCandidates 是否存在候选节点。
     * @return 预过滤结果。
     */
    public PreFilterResult evaluate(double topScore, boolean hasCandidates) {
        if (!hasCandidates) {
            LOGGER.info("Rule pre-filter: no candidates, action=BRANCH skipLlm=true");
            return new PreFilterResult("BRANCH", "no recall candidates available", true);
        }
        if (topScore >= autoMergeThreshold) {
            LOGGER.info("Rule pre-filter: topScore={} >= threshold={}, action=AUTO_MERGE skipLlm=true",
                    topScore, autoMergeThreshold);
            return new PreFilterResult("MERGE",
                    "rule_decision=AUTO_MERGE top_score=%.4f >= threshold=%.2f".formatted(topScore, autoMergeThreshold),
                    true);
        }
        if (topScore < autoBranchThreshold) {
            LOGGER.info("Rule pre-filter: topScore={} < threshold={}, action=AUTO_BRANCH skipLlm=true",
                    topScore, autoBranchThreshold);
            return new PreFilterResult("BRANCH",
                    "rule_decision=AUTO_BRANCH top_score=%.4f < threshold=%.2f".formatted(topScore, autoBranchThreshold),
                    true);
        }
        LOGGER.info("Rule pre-filter: topScore={} in middle range, skipLlm=false", topScore);
        return new PreFilterResult("REVIEW", "top_score=%.4f in LLM evaluation range".formatted(topScore), false);
    }
}
