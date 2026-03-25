import { useCallback } from "react";
import { useReactFlow } from "@xyflow/react";

/**
 * Hook for graph canvas interactions:
 * - Center view on selected node
 * - Highlight neighbor nodes/edges, dim the rest
 */
export function useGraphInteractions() {
  const { setCenter, getEdges, getNode, getViewport } = useReactFlow();

  const focusNode = useCallback(
    (nodeId: string, delayMs = 0) => {
      const node = getNode(nodeId);
      if (!node) return;

      const performCenter = () => {
        const { zoom: currentZoom } = getViewport();
        setCenter(
          node.position.x,
          node.position.y,
          { zoom: Math.max(currentZoom, 0.8), duration: 600 },
        );
      };

      if (delayMs > 0) {
        setTimeout(performCenter, delayMs);
      } else {
        performCenter();
      }

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
    [getEdges, getNode, getViewport, setCenter],
  );

  const resetFocus = useCallback(() => {
    // Caller resets opacity via store
  }, []);

  return { focusNode, resetFocus };
}
