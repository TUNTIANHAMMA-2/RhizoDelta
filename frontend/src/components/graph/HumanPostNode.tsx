import { Handle, Position, type NodeProps } from "@xyflow/react";
import type { GraphNodeDTO } from "../../api/types";
import { useGraphStore } from "../../stores/graphStore";
import { NodeActionToolbar } from "./NodeActionToolbar";

const HIDDEN_HANDLE_STYLE = {
  opacity: 0,
  pointerEvents: "none",
} as const;

function VersionHandles() {
  return (
    <>
      <Handle id="source-top" type="source" position={Position.Top} style={HIDDEN_HANDLE_STYLE} />
      <Handle id="source-right" type="source" position={Position.Right} style={HIDDEN_HANDLE_STYLE} />
      <Handle id="source-bottom" type="source" position={Position.Bottom} style={HIDDEN_HANDLE_STYLE} />
      <Handle id="source-left" type="source" position={Position.Left} style={HIDDEN_HANDLE_STYLE} />
      <Handle id="target-top" type="target" position={Position.Top} style={HIDDEN_HANDLE_STYLE} />
      <Handle id="target-right" type="target" position={Position.Right} style={HIDDEN_HANDLE_STYLE} />
      <Handle id="target-bottom" type="target" position={Position.Bottom} style={HIDDEN_HANDLE_STYLE} />
      <Handle id="target-left" type="target" position={Position.Left} style={HIDDEN_HANDLE_STYLE} />
    </>
  );
}

export function HumanPostNode({ data, selected }: NodeProps) {
  const node = data as unknown as GraphNodeDTO;
  const zoom = useGraphStore((s) => s.semanticZoom);
  const isOptimistic = (data as any)?.isOptimistic === true;

  const baseClass = "node-base node-human";
  const stateClass = selected ? " selected" : "";
  const optClass = isOptimistic ? " node-optimistic" : "";
  const zoomClass = ` node-${zoom}`;

  return (
    <>
      <div className={`${baseClass}${stateClass}${optClass}${zoomClass}`}>
        <VersionHandles />
        
        <div className="node-content-micro" />
        
        <div className="node-content-mini">
          <span>{(node.content ?? "").slice(0, 6)}</span>
        </div>

        <div className="node-content-normal">
          <div className="node-header">
            <span>{node.author_id ?? "Anonymous"}</span>
            <span>{new Date(node.created_at).toLocaleDateString()}</span>
          </div>
          <div className="node-body">
            {node.content ?? node.summary_content ?? ""}
          </div>
        </div>
      </div>
      {selected && zoom === "normal" && !isOptimistic && (
        <div style={{ position: "absolute", top: "calc(100% + 12px)", left: "50%", transform: "translateX(-50%)", zIndex: 10 }}>
          <NodeActionToolbar nodeId={node.node_id} />
        </div>
      )}
    </>
  );
}
