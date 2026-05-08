import { memo } from "react";
import {
  BaseEdge,
  type EdgeProps,
} from "@xyflow/react";

function buildOrganicPath(sourceX: number, sourceY: number, targetX: number, targetY: number): [string, number, number] {
  const dx = targetX - sourceX;
  const dy = targetY - sourceY;
  const dist = Math.sqrt(dx * dx + dy * dy);
  if (dist === 0) return [`M ${sourceX},${sourceY} L ${targetX},${targetY}`, sourceX, sourceY];
  const curvature = Math.min(dist * 0.2, 50);
  const nx = -dy / dist;
  const ny = dx / dist;
  const cx = (sourceX + targetX) / 2 + nx * curvature;
  const cy = (sourceY + targetY) / 2 + ny * curvature;
  const labelX = (sourceX + targetX) / 2;
  const labelY = (sourceY + targetY) / 2;
  return [`M ${sourceX},${sourceY} Q ${cx},${cy} ${targetX},${targetY}`, labelX, labelY];
}

/**
 * Custom bezier that adapts control point offsets based on the actual
 * horizontal/vertical distance between source and target.
 * This avoids the "right-angle stub" that getBezierPath produces when
 * sourcePosition=Bottom/targetPosition=Top but nodes are far apart horizontally.
 */
function buildAdaptiveBezier(
  sourceX: number, sourceY: number,
  targetX: number, targetY: number,
): [string, number, number] {
  const dy = targetY - sourceY;
  const absDy = Math.abs(dy);
  // Control point vertical offset: at least 40px, scales with vertical distance
  const cpOffset = Math.max(40, absDy * 0.4);

  const cp1x = sourceX;
  const cp1y = sourceY + cpOffset;
  const cp2x = targetX;
  const cp2y = targetY - cpOffset;

  const path = `M ${sourceX},${sourceY} C ${cp1x},${cp1y} ${cp2x},${cp2y} ${targetX},${targetY}`;
  const labelX = (sourceX + targetX) / 2;
  const labelY = (sourceY + targetY) / 2;
  return [path, labelX, labelY];
}

function resolveEdgePath(props: EdgeProps, routeKind: string) {
  if (routeKind === "explore") {
    return buildOrganicPath(props.sourceX, props.sourceY, props.targetX, props.targetY);
  }

  return buildAdaptiveBezier(props.sourceX, props.sourceY, props.targetX, props.targetY);
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
