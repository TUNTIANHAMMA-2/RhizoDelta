import { memo, useCallback, useState } from "react";
import { BaseEdge, getSmoothStepPath, type EdgeProps } from "@xyflow/react";

interface AssociationEdgeData {
  associationType: string;
  confidence?: number;
  createdAt?: string;
}

const TYPE_COLORS: Record<string, string> = {
  CONCEPTUAL_OVERLAP: "var(--color-accent)",
  RELATES_TO: "var(--color-text-tertiary)",
};

function formatDate(iso?: string): string {
  if (!iso) return "";
  try {
    return new Date(iso).toLocaleDateString("zh-CN", {
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    });
  } catch {
    return iso;
  }
}

function typeLabel(type: string): string {
  switch (type) {
    case "CONCEPTUAL_OVERLAP":
      return "概念重叠";
    case "RELATES_TO":
      return "关联";
    default:
      return type;
  }
}

export const AssociationEdge = memo(function AssociationEdge(props: EdgeProps) {
  const [hovered, setHovered] = useState(false);

  const data = (props.data ?? {}) as unknown as AssociationEdgeData;
  const strokeColor = TYPE_COLORS[data.associationType] ?? "var(--color-text-tertiary)";

  const [edgePath, labelX, labelY] = getSmoothStepPath({
    sourceX: props.sourceX,
    sourceY: props.sourceY,
    targetX: props.targetX,
    targetY: props.targetY,
    sourcePosition: props.sourcePosition,
    targetPosition: props.targetPosition,
    borderRadius: 20,
  });

  const onMouseEnter = useCallback(() => setHovered(true), []);
  const onMouseLeave = useCallback(() => setHovered(false), []);

  return (
    <>
      {/* Invisible wider hit area for hover detection */}
      <path
        d={edgePath}
        fill="none"
        stroke="transparent"
        strokeWidth={12}
        onMouseEnter={onMouseEnter}
        onMouseLeave={onMouseLeave}
        style={{ cursor: "default" }}
      />
      <BaseEdge
        id={props.id}
        path={edgePath}
        style={{
          stroke: strokeColor,
          strokeWidth: hovered ? 2.5 : 1.5,
          strokeDasharray: "6 4",
          transition: "stroke-width 120ms ease",
          pointerEvents: "none",
        }}
      />
      {hovered && (
        <foreignObject
          x={labelX - 80}
          y={labelY - 50}
          width={160}
          height={80}
          style={{ overflow: "visible", pointerEvents: "none" }}
        >
          <div
            style={{
              background: "var(--color-bg-primary)",
              border: "1px solid var(--color-border-default)",
              borderRadius: "var(--radius-sm)",
              boxShadow: "var(--shadow-sm)",
              padding: "var(--space-2)",
              fontFamily: "var(--font-ui)",
              fontSize: "var(--font-size-xs)",
              color: "var(--color-text-primary)",
              display: "flex",
              flexDirection: "column",
              gap: "2px",
              whiteSpace: "nowrap",
              width: "fit-content",
            }}
          >
            <span style={{ fontWeight: 600 }}>
              {typeLabel(data.associationType)}
            </span>
            {data.confidence != null && (
              <span style={{ color: "var(--color-text-secondary)" }}>
                置信度: {(data.confidence * 100).toFixed(0)}%
              </span>
            )}
            {data.createdAt && (
              <span style={{ color: "var(--color-text-tertiary)" }}>
                {formatDate(data.createdAt)}
              </span>
            )}
          </div>
        </foreignObject>
      )}
    </>
  );
});
