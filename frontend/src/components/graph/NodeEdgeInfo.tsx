import { memo, useMemo } from "react";
import { useGraphStore } from "../../stores/graphStore";

export const NodeEdgeInfo = memo(function NodeEdgeInfo({
  nodeId,
}: {
  nodeId: string;
}) {
  const edges = useGraphStore((s) => s.edges);

  const count = useMemo(() => {
    return edges.filter((e) => e.source === nodeId || e.target === nodeId).length;
  }, [edges, nodeId]);

  if (count === 0) return null;

  return (
    <div className="node-edge-info">
      <span className="node-edge-badge">{count}</span>
    </div>
  );
});
