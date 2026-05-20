import { memo, useMemo, useState } from "react";
import { type NodeProps } from "@xyflow/react";
import type { GraphNodeDTO } from "../../api/types";
import { useGraphStore } from "../../stores/graphStore";
import { NodeActionToolbar } from "./NodeActionToolbar";
import { VersionHandles } from "./VersionHandles";
import { NodeEdgeInfo } from "./NodeEdgeInfo";
import { QualityBadge } from "./QualityBadge";
import { stripMarkdown } from "../../lib/markdown";
import { useNodeDwellEvent } from "../../hooks/useGraphInteractions";

export const ConsensusNode = memo(function ConsensusNode({ data, selected }: NodeProps) {
  const node = data as unknown as GraphNodeDTO;
  const zoom = useGraphStore((s) => s.semanticZoom);
  const [hovered, setHovered] = useState(false);
  const dwellRef = useNodeDwellEvent(node.node_id);

  const baseClass = "node-base node-consensus";
  const stateClass = selected ? " selected" : "";
  const zoomClass = ` node-${zoom}`;

  const plainText = useMemo(
    () => stripMarkdown(node.summary_content ?? node.content),
    [node.summary_content, node.content],
  );

  return (
    <div
      ref={dwellRef}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      className="relative w-full h-full"
      role="article"
      tabIndex={0}
      aria-label={`AI Consensus: ${plainText.slice(0, 50)}`}
      aria-selected={selected}
    >
      <div className={`${baseClass}${stateClass}${zoomClass}`}>
        {selected && <div className="rd-marker-selected" />}
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
              <span>AI Consensus</span>
              <span className="flex items-center gap-1">
                {node.quality_overall != null && <QualityBadge qualityOverall={node.quality_overall} />}
                &middot; {new Date(node.created_at).toLocaleDateString()}
              </span>
            </div>
            <div className="node-body">{plainText}</div>
          </div>
        )}
      </div>
      {hovered && zoom === "normal" && <NodeEdgeInfo nodeId={node.node_id} />}
      {selected && zoom === "normal" && (
        <div className="absolute top-[calc(100%+12px)] left-1/2 -translate-x-1/2 z-30">
          <NodeActionToolbar nodeId={node.node_id} />
        </div>
      )}
    </div>
  );
});
