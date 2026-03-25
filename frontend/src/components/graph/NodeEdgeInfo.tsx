import { memo, useMemo } from "react";
import { useGraphStore } from "../../stores/graphStore";
import { stripMarkdown } from "../../lib/markdown";

const REL_LABELS: Record<string, string> = {
  CONTINUES_FROM: "Continues from",
  BRANCHED_FROM: "Branched from",
  MERGED_INTO: "Merged into",
  CONVERGED_FROM: "Converged from",
  MATERIALIZED_FROM: "Materialized from",
  SYNTHESIZED_FROM: "Synthesized from",
  CROSS_SYNTHESIZED_FROM: "Cross-synthesized from",
};

function formatRelType(type: string) {
  return REL_LABELS[type] ?? type.replace(/_/g, " ");
}

export const NodeEdgeInfo = memo(function NodeEdgeInfo({
  nodeId,
}: {
  nodeId: string;
}) {
  const edges = useGraphStore((s) => s.edges);
  const nodes = useGraphStore((s) => s.nodes);

  const connected = useMemo(() => {
    return edges
      .filter((e) => e.source === nodeId || e.target === nodeId)
      .map((e) => {
        const isOutgoing = e.source === nodeId;
        const otherNodeId = isOutgoing ? e.target : e.source;
        const otherNode = nodes.get(otherNodeId);
        
        let otherNodeText = otherNodeId.slice(0, 6);
        if (otherNode) {
          const rawText = otherNode.content ?? otherNode.summary_content;
          const stripped = stripMarkdown(rawText);
          if (stripped) {
            otherNodeText = stripped.slice(0, 12) + (stripped.length > 12 ? "..." : "");
          }
        }

        return {
          key: `${e.source}-${e.type}-${e.target}`,
          type: e.type,
          isOutgoing,
          createdAt: e.created_at,
          otherNodeId,
          otherNodeText,
        };
      });
  }, [edges, nodes, nodeId]);

  if (connected.length === 0) return null;

  return (
    <div className="node-edge-info">
      {connected.map((edge) => (
        <div key={edge.key} className="node-edge-item" title={edge.otherNodeId}>
          <span className="node-edge-arrow">
            {edge.isOutgoing ? "→" : "←"}
          </span>
          <span className="node-edge-type">{formatRelType(edge.type)}</span>
          <span className="node-edge-target" style={{ marginLeft: "6px", fontWeight: 600, color: "var(--color-text-primary)" }}>
            "{edge.otherNodeText}"
          </span>
          <span className="node-edge-time" style={{ marginLeft: "auto", opacity: 0.6 }}>
            {new Date(edge.createdAt).toLocaleDateString()}
          </span>
        </div>
      ))}
    </div>
  );
});
