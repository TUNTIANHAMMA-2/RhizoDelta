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
    <div className="border border-border-default rounded-md p-3 mt-3 bg-bg-secondary text-xs">
      <div className="font-semibold mb-2 text-sm">AI 决策解释</div>

      {/* Action + Confidence */}
      <div className="flex items-center gap-2 mb-2">
        <span className="font-semibold">
          {ACTION_LABELS[explanation.action] ?? explanation.action}
        </span>
        <div className="flex-1 h-[6px] bg-border-default rounded-[3px] overflow-hidden">
          <div
            className="h-full rounded-[3px] transition-[width] duration-300"
            style={{
              width: `${confidencePercent}%`,
              background: barColor,
            }}
          />
        </div>
        <span className="text-text-secondary min-w-[36px] text-right">
          {confidencePercent}%
        </span>
      </div>

      <div className="mb-2">
        <span className="text-text-secondary">原因: </span>
        {explanation.reason}
      </div>

      {explanation.reflectionSummary && (
        <div className="mb-2">
          <span className="text-text-secondary">反思: </span>
          {explanation.reflectionSummary}
        </div>
      )}

      {explanation.candidateComparison && (
        <details className="text-text-secondary">
          <summary className="cursor-pointer select-none">候选对比</summary>
          <pre className="whitespace-pre-wrap break-words text-xs mt-1 max-h-[120px] overflow-y-auto">
            {explanation.candidateComparison}
          </pre>
        </details>
      )}
    </div>
  );
}
