import { useCallback } from "react";
import { useReactFlow } from "@xyflow/react";
import { useGraphStore } from "../stores/graphStore";

/**
 * Hook for graph canvas interactions:
 * - Center view on selected node
 * - Highlight neighbor nodes/edges, dim the rest
 */
export function useGraphInteractions() {
  const { setCenter, getEdges } = useReactFlow();
  const rfNodes = useGraphStore((s) => s.rfNodes);

  const focusNode = useCallback(
    (nodeId: string) => {
      const node = rfNodes.find((n) => n.id === nodeId);
      if (!node) return;

      // Center canvas on selected node
      setCenter(
        node.position.x + 140,
        node.position.y + 50,
        { zoom: 1, duration: 600 },
      );

      // Find connected node IDs
      const edges = getEdges();
      const connectedEdgeIds = new Set<string>();
      const connectedNodeIds = new Set<string>([nodeId]);

      for (const edge of edges) {
        if (edge.source === nodeId || edge.target === nodeId) {
          connectedEdgeIds.add(edge.id);
          connectedNodeIds.add(edge.source);
          connectedNodeIds.add(edge.target);
        }
      }

      return { connectedNodeIds, connectedEdgeIds };
    },
    [rfNodes, setCenter, getEdges],
  );

  const resetFocus = useCallback(() => {
    // Caller resets opacity via store
  }, []);

  return { focusNode, resetFocus };
}
