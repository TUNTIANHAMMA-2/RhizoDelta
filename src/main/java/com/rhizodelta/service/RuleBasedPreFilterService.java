package com.rhizodelta.service;

import com.rhizodelta.config.ModelRouterConfig;
import com.rhizodelta.domain.ai.PreFilterResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
