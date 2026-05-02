import { useCallback, useEffect, useRef } from "react";
import { useReactFlow } from "@xyflow/react";
import { useGraphStore } from "../stores/graphStore";
import { useUiStore } from "../stores/uiStore";

/**
 * Hook for graph canvas interactions:
 * - Center view on selected node
 * - Highlight neighbor nodes/edges, dim the rest
 * - Auto-expand boundary nodes after selection dwell
 */
export function useGraphInteractions() {
  const { setCenter, getEdges, getNode, getViewport } = useReactFlow();
  // 当右侧 detail/edit/review 面板打开时，画布 DOM 已经按 flex 缩小到剩余宽度。
  // setCenter 把节点坐标对到画布 DOM 中心，这本身就是可见区域中心 —— 但用户更喜欢
  // 节点稍稍偏左，与右侧面板留出阅读距离。下面用 zoom 比例把一段固定的屏幕像素
  // 转换成 flow 坐标偏移。
  const rightPanelOpen = useUiStore(
    (s) => s.rightPanelMode !== "hidden",
  );

  const focusNode = useCallback(
    (nodeId: string, delayMs = 0) => {
      const node = getNode(nodeId);
      if (!node) return;

      const performCenter = () => {
        const { zoom: currentZoom } = getViewport();
        const targetZoom = Math.max(currentZoom, 0.8);
        // 让节点出现在可见区域偏左 ~12% 的位置，给右侧面板留呼吸感。
        const offsetScreenPx = rightPanelOpen ? 80 : 0;
        const offsetFlow = offsetScreenPx / targetZoom;
        setCenter(
          node.position.x + offsetFlow,
          node.position.y,
          { zoom: targetZoom, duration: 600 },
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
    [getEdges, getNode, getViewport, rightPanelOpen, setCenter],
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
