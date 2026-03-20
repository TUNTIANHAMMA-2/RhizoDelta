import {
  BaseEdge,
  getSmoothStepPath,
  type EdgeProps,
} from "@xyflow/react";

export function VersionEdge(props: EdgeProps) {
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
    <g className="version-edge-group">
      <style>{`
        .version-edge-group { cursor: pointer; }
        .version-edge-group .edge-path {
          stroke-width: 1.5px;
          stroke: var(--color-edge-default);
          transition: stroke-width 120ms ease, stroke 120ms ease;
        }
        .version-edge-group:hover .edge-path {
          stroke-width: 2.5px;
          stroke: var(--color-text-secondary);
        }
        .version-edge-group .edge-tooltip {
          opacity: 0;
          pointer-events: none;
          transition: opacity 120ms ease;
        }
        .version-edge-group:hover .edge-tooltip {
          opacity: 1;
        }
      `}</style>
      {/* Invisible wider hit area for easier hover */}
      <path
        d={edgePath}
        fill="none"
        stroke="transparent"
        strokeWidth={20}
        style={{ pointerEvents: "stroke" }}
      />
      <BaseEdge
        id={props.id}
        path={edgePath}
        style={props.style}
        className="edge-path"
        markerEnd={props.markerEnd}
      />
      <foreignObject
        className="edge-tooltip"
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
    </g>
  );
}
