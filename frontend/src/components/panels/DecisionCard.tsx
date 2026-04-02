import type { DecisionExplanation } from "../../api/types";

interface DecisionCardProps {
  explanation: DecisionExplanation;
}

const ACTION_LABELS: Record<string, string> = {
  MERGE: "并入共识",
  BRANCH: "分出支线",
  REVIEW: "等待复核",
};

export function DecisionCard({ explanation }: DecisionCardProps) {
  const confidencePercent = Math.round(explanation.confidence * 100);
  const barColor =
    confidencePercent >= 70
      ? "var(--color-success)"
      : confidencePercent >= 40
        ? "var(--color-warning, #e6a817)"
        : "var(--color-danger)";

  return (
    <div
      style={{
        border: "1px solid var(--color-border-default)",
        borderRadius: "var(--radius-md, 6px)",
        padding: "var(--space-3)",
        marginTop: "var(--space-3)",
        background: "var(--color-bg-secondary, #fafafa)",
        fontSize: "var(--font-size-xs)",
      }}
    >
      <div
        style={{
          fontWeight: 600,
          marginBottom: "var(--space-2)",
          fontSize: "var(--font-size-sm)",
        }}
      >
        AI 决策解释
      </div>

      {/* Action + Confidence */}
      <div
        style={{
          display: "flex",
          alignItems: "center",
          gap: "var(--space-2)",
          marginBottom: "var(--space-2)",
        }}
      >
        <span style={{ fontWeight: 600 }}>
          {ACTION_LABELS[explanation.action] ?? explanation.action}
        </span>
        <div
          style={{
            flex: 1,
            height: 6,
            background: "var(--color-border-default)",
            borderRadius: 3,
            overflow: "hidden",
          }}
        >
          <div
            style={{
              width: `${confidencePercent}%`,
              height: "100%",
              background: barColor,
              borderRadius: 3,
              transition: "width 0.3s ease",
            }}
          />
        </div>
        <span style={{ color: "var(--color-text-secondary)", minWidth: 36, textAlign: "right" }}>
          {confidencePercent}%
        </span>
      </div>

      {/* Reason */}
      <div style={{ marginBottom: "var(--space-2)" }}>
        <span style={{ color: "var(--color-text-secondary)" }}>原因: </span>
        {explanation.reason}
      </div>

      {/* Reflection Summary */}
      {explanation.reflectionSummary && (
        <div style={{ marginBottom: "var(--space-2)" }}>
          <span style={{ color: "var(--color-text-secondary)" }}>反思: </span>
          {explanation.reflectionSummary}
        </div>
      )}

      {/* Candidate Comparison (collapsed if long) */}
      {explanation.candidateComparison && (
        <details style={{ color: "var(--color-text-secondary)" }}>
          <summary style={{ cursor: "pointer", userSelect: "none" }}>候选对比</summary>
          <pre
            style={{
              whiteSpace: "pre-wrap",
              wordBreak: "break-word",
              fontSize: "var(--font-size-xs)",
              marginTop: "var(--space-1)",
              maxHeight: 120,
              overflowY: "auto",
            }}
          >
            {explanation.candidateComparison}
          </pre>
        </details>
      )}
    </div>
  );
}
