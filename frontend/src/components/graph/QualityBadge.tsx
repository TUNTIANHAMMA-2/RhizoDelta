import { memo } from "react";

interface QualityBadgeProps {
  qualityOverall: number;
}

function resolveStyle(score: number): { color: string; label: string } {
  if (score >= 0.8) return { color: "var(--color-success, #22c55e)", label: "优质" };
  if (score >= 0.6) return { color: "var(--color-accent, #3b82f6)", label: "良好" };
  if (score >= 0.4) return { color: "var(--color-text-tertiary, #9ca3af)", label: "一般" };
  return { color: "var(--color-warning, #f59e0b)", label: "待改进" };
}

export const QualityBadge = memo(function QualityBadge({ qualityOverall }: QualityBadgeProps) {
  const { color, label } = resolveStyle(qualityOverall);
  const percent = Math.round(qualityOverall * 100);

  return (
    <span
      title={`质量评分: ${percent}% (${label})`}
      style={{
        display: "inline-flex",
        alignItems: "center",
        gap: 2,
        fontSize: 9,
        fontWeight: 600,
        color,
        background: "var(--color-bg-primary, #fff)",
        border: `1px solid ${color}`,
        borderRadius: 8,
        padding: "0 4px",
        lineHeight: "16px",
        whiteSpace: "nowrap",
      }}
    >
      {percent}
    </span>
  );
});
