import { useState } from "react";
import {
  BaseEdge,
  getSmoothStepPath,
  type EdgeProps,
} from "@xyflow/react";

export function VersionEdge(props: EdgeProps) {
  const [hovered, setHovered] = useState(false);
  const [edgePath, labelX, labelY] = getSmoothStepPath({
    sourceX: props.sourceX,
    sourceY: props.sourceY,
    targetX: props.targetX,
    targetY: props.targetY,
    sourcePosition: props.sourcePosition,
    targetPosition: props.targetPosition,
  });

  const relType = (props.data as { relType?: string })?.relType ?? "";
  const createdAt = (props.data as { createdAt?: string })?.createdAt ?? "";

  return (
    <g
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
    >
      {/* Invisible wider hit area for easier hover */}
      <path
        d={edgePath}
        fill="none"
        stroke="transparent"
        strokeWidth={20}
      />
      <BaseEdge
        id={props.id}
        path={edgePath}
        style={{
          ...props.style,
          strokeWidth: hovered ? 2.5 : 1.5,
          stroke: hovered
            ? "var(--color-text-secondary)"
            : "var(--color-edge-default)",
          transition: "stroke-width 120ms ease, stroke 120ms ease",
        }}
        markerEnd={props.markerEnd}
      />
      {hovered && (
        <foreignObject
          width={180}
          height={44}
          x={labelX - 90}
          y={labelY - 22}
          style={{ pointerEvents: "none" }}
        >
          <div className="version-edge-tooltip">
            <div>{relType.replace(/_/g, " ")}</div>
            {createdAt && (
              <div style={{ fontSize: 10, opacity: 0.7 }}>
                {new Date(createdAt).toLocaleString()}
              </div>
            )}
          </div>
        </foreignObject>
      )}
    </g>
  );
}
