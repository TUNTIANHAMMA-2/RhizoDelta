import { memo } from "react";
import {
  BaseEdge,
  getBezierPath,
  getStraightPath,
  getSmoothStepPath,
  type EdgeProps,
} from "@xyflow/react";

function resolveEdgePath(props: EdgeProps, routeKind: string) {
  if (routeKind === "explore") {
    return getBezierPath({
      sourceX: props.sourceX,
      sourceY: props.sourceY,
      targetX: props.targetX,
      targetY: props.targetY,
      sourcePosition: props.sourcePosition,
      targetPosition: props.targetPosition,
      curvature: 0.32,
    });
  }

  if (routeKind === "branch") {
    return getSmoothStepPath({
      sourceX: props.sourceX,
      sourceY: props.sourceY,
      targetX: props.targetX,
      targetY: props.targetY,
      sourcePosition: props.sourcePosition,
      targetPosition: props.targetPosition,
      borderRadius: 32,
      offset: 30,
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
    <BaseEdge
      id={props.id}
      path={edgePath}
      style={edgeStyle}
      className="edge-path"
      markerEnd={props.markerEnd}
    />
  );
});
