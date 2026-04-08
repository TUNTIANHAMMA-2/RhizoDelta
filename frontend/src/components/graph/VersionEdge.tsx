import { memo } from "react";
import {
  BaseEdge,
  getBezierPath,
  getStraightPath,
  getSmoothStepPath,
  type EdgeProps,
} from "@xyflow/react";

function buildOrganicPath(sourceX: number, sourceY: number, targetX: number, targetY: number): [string, number, number] {
  const dx = targetX - sourceX;
  const dy = targetY - sourceY;
  const dist = Math.sqrt(dx * dx + dy * dy);
  if (dist === 0) return [`M ${sourceX},${sourceY} L ${targetX},${targetY}`, sourceX, sourceY];
  // 曲线的轻微偏移，制造天然根茎的弧度而绝不打结绕圈
  const curvature = Math.min(dist * 0.2, 50);
  const nx = -dy / dist;
  const ny = dx / dist;
  const cx = (sourceX + targetX) / 2 + nx * curvature;
  const cy = (sourceY + targetY) / 2 + ny * curvature;
  
  const labelX = (sourceX + targetX) / 2;
  const labelY = (sourceY + targetY) / 2;
  return [`M ${sourceX},${sourceY} Q ${cx},${cy} ${targetX},${targetY}`, labelX, labelY];
}

function resolveEdgePath(props: EdgeProps, routeKind: string) {
  if (routeKind === "explore") {
    // 使用纯正的无方向有机弧线，完美解决任何句柄切线的交叉问题
    return buildOrganicPath(props.sourceX, props.sourceY, props.targetX, props.targetY);
  }

  if (routeKind === "branch") {
    return getSmoothStepPath({
      sourceX: props.sourceX,
      sourceY: props.sourceY,
      targetX: props.targetX,
      targetY: props.targetY,
      sourcePosition: props.sourcePosition,
      targetPosition: props.targetPosition,
      borderRadius: 16,
    });
  }

  if (routeKind === "continue" && props.sourceX === props.targetX) {
    return getStraightPath({
      sourceX: props.sourceX,
      sourceY: props.sourceY,
      targetX: props.targetX,
      targetY: props.targetY,
    });
  }

  return getSmoothStepPath({
    sourceX: props.sourceX,
    sourceY: props.sourceY,
    targetX: props.targetX,
    targetY: props.targetY,
    sourcePosition: props.sourcePosition,
    targetPosition: props.targetPosition,
    borderRadius: routeKind === "continue" ? 0 : 14,
    offset: routeKind === "continue" ? 0 : 18,
  });
}

export const VersionEdge = memo(function VersionEdge(props: EdgeProps) {
  const routeKind = (props.data as { routeKind?: string })?.routeKind ?? "vertical";
  const viewMode = (props.data as { viewMode?: string })?.viewMode ?? "lineage";
  const [edgePath] = resolveEdgePath(props, routeKind);
  const edgeStyle = {
    ...props.style,
    strokeWidth: routeKind === "continue" ? 1.8 : 1.5,
    opacity: viewMode === "explore" ? 0.82 : 1,
  };

  return (
    <>
      <BaseEdge
        id={props.id}
        path={edgePath}
        style={edgeStyle}
        className="edge-path"
        markerEnd={props.markerEnd}
      />
      {viewMode === "explore" && (
        <path
          d={edgePath}
          fill="none"
          style={{ ...edgeStyle, strokeWidth: parseFloat(String(edgeStyle.strokeWidth)) * 0.8 }}
          className="edge-path edge-path--flow"
        />
      )}
    </>
  );
});
