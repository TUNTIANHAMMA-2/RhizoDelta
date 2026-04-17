import { useCallback, useEffect, useRef } from "react";
import { useReactFlow } from "@xyflow/react";
import { useGraphStore } from "../stores/graphStore";

/**
 * Hook for graph canvas interactions:
 * - Center view on selected node
 * - Highlight neighbor nodes/edges, dim the rest
 * - Auto-expand boundary nodes after selection dwell
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

  // ── Consume pendingFocusNodeId from store ──
  const pendingFocusNodeId = useGraphStore((s) => s.pendingFocusNodeId);
  const clearPendingFocus = useGraphStore((s) => s.clearPendingFocus);

  useEffect(() => {
    if (!pendingFocusNodeId) return;
    clearPendingFocus();
    // Small delay to let layout settle after selection
    focusNode(pendingFocusNodeId, 100);
  }, [pendingFocusNodeId, clearPendingFocus, focusNode]);

  // ── Auto-expand boundary nodes after selection dwell ──
  const selectedNodeId = useGraphStore((s) => s.selectedNodeId);
  const expandChildren = useGraphStore((s) => s.expandChildren);
  const getBoundaryNodeIds = useGraphStore((s) => s.getBoundaryNodeIds);
  const expandTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    // Clear any pending timer when selectedNodeId changes
    if (expandTimerRef.current) {
      clearTimeout(expandTimerRef.current);
      expandTimerRef.current = null;
    }

    if (!selectedNodeId) return;

    const boundaryIds = getBoundaryNodeIds();
    if (!boundaryIds.includes(selectedNodeId)) return;

    expandTimerRef.current = setTimeout(() => {
      expandTimerRef.current = null;
      expandChildren(selectedNodeId);
    }, 1500);

    return () => {
      if (expandTimerRef.current) {
        clearTimeout(expandTimerRef.current);
        expandTimerRef.current = null;
      }
    };
  }, [selectedNodeId, expandChildren, getBoundaryNodeIds]);

  return { focusNode, resetFocus };
}
