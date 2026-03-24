import { useCallback } from "react";
import { useReactFlow } from "@xyflow/react";

/**
 * Hook for graph canvas interactions:
 * - Center view on selected node
 * - Highlight neighbor nodes/edges, dim the rest
 */
export function useGraphInteractions() {
  const { setCenter, getEdges, getNode } = useReactFlow();

  const focusNode = useCallback(
    (nodeId: string) => {
      const node = getNode(nodeId);
      if (!node) return;

      // Center canvas on selected node
      setCenter(
        node.position.x,
        node.position.y,
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
    [getEdges, getNode, setCenter],
  );

  const resetFocus = useCallback(() => {
    // Caller resets opacity via store
  }, []);

  return { focusNode, resetFocus };
}
