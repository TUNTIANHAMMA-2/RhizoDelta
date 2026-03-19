import { Handle, Position, type NodeProps } from "@xyflow/react";
import type { GraphNodeDTO } from "../../api/types";
import { useGraphStore } from "../../stores/graphStore";

export function HumanPostNode({ data, selected }: NodeProps) {
  const node = data as unknown as GraphNodeDTO;
  const zoom = useGraphStore((s) => s.semanticZoom);

  const isOptimistic = (data as any)?.isOptimistic === true;

  if (zoom === "micro") {
    return (
      <div
        className={`node-circle node-circle--sm${selected ? " selected" : ""}`}
        style={{ borderColor: "var(--color-node-human)" }}
      >
        <Handle type="target" position={Position.Top} />
        <Handle type="source" position={Position.Bottom} />
      </div>
    );
  }

  if (zoom === "mini") {
    return (
      <div
        className={`node-circle node-circle--md${selected ? " selected" : ""}`}
        style={{ borderColor: "var(--color-node-human)" }}
      >
        <Handle type="target" position={Position.Top} />
        <span>{(node.content ?? "").slice(0, 6)}</span>
        <Handle type="source" position={Position.Bottom} />
      </div>
    );
  }

  return (
    <div className={`node-card node-card--human${selected ? " selected" : ""}${isOptimistic ? " node-card--optimistic" : ""}`}>
      <Handle type="target" position={Position.Top} />
      <div className="node-card__header">
        <span>{node.author_id ?? "Anonymous"}</span>
        <span>{new Date(node.created_at).toLocaleDateString()}</span>
      </div>
      <div className="node-card__body">
        {node.content ?? node.summary_content ?? ""}
      </div>
      <Handle type="source" position={Position.Bottom} />
    </div>
  );
}
