import { memo, useMemo, useState } from "react";
import { type NodeProps } from "@xyflow/react";
import type { GraphNodeDTO } from "../../api/types";
import { useGraphStore } from "../../stores/graphStore";
import { NodeActionToolbar } from "./NodeActionToolbar";
import { VersionHandles } from "./VersionHandles";
import { NodeEdgeInfo } from "./NodeEdgeInfo";
import { stripMarkdown } from "../../lib/markdown";

export const HumanPostNode = memo(function HumanPostNode({ data, selected }: NodeProps) {
  const node = data as unknown as GraphNodeDTO;
  const zoom = useGraphStore((s) => s.semanticZoom);
  const isOptimistic = (data as any)?.isOptimistic === true;
  const [hovered, setHovered] = useState(false);

  const baseClass = "node-base node-human";
  const stateClass = selected ? " selected" : "";
  const optClass = isOptimistic ? " node-optimistic" : "";
  const zoomClass = ` node-${zoom}`;

  const plainText = useMemo(
    () => stripMarkdown(node.content ?? node.summary_content),
    [node.content, node.summary_content],
  );

  return (
    <div
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      style={{ position: "relative", width: "100%", height: "100%" }}
      role="article"
      tabIndex={0}
      aria-label={`Human post: ${plainText.slice(0, 50)}`}
      aria-selected={selected}
    >
      <div className={`${baseClass}${stateClass}${optClass}${zoomClass}`}>
        {selected && <div className="rd-marker-selected" />}
        {isOptimistic && <div className="rd-marker-edit" />}
        <VersionHandles />

        {zoom === "micro" && <div className="node-content-micro" />}

        {zoom === "mini" && (
          <div className="node-content-mini">
            <span>{plainText.slice(0, 6)}</span>
          </div>
        )}

        {zoom === "normal" && (
          <div className="node-content-normal">
            <div className="node-header">
              <span>{node.author_id ?? "Anonymous"}</span>
              <span>{new Date(node.created_at).toLocaleDateString()}</span>
            </div>
            <div className="node-body">
              {plainText}
            </div>
          </div>
        )}
      </div>
      {hovered && zoom === "normal" && (
        <NodeEdgeInfo nodeId={node.node_id} />
      )}
      {selected && zoom === "normal" && !isOptimistic && (
        <div style={{ position: "absolute", top: "calc(100% + 12px)", left: "50%", transform: "translateX(-50%)", zIndex: 10 }}>
          <NodeActionToolbar nodeId={node.node_id} />
        </div>
      )}
    </div>
  );
});
