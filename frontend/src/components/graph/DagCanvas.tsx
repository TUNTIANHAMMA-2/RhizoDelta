import {
  ReactFlow,
  Background,
  BackgroundVariant,
  MiniMap,
  type Node as FlowNode,
  useOnViewportChange,
  type Viewport,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { useCallback, useEffect, useMemo, useRef } from "react";
import { useGraphStore } from "../../stores/graphStore";
import { useUiStore } from "../../stores/uiStore";
import { createLineageSimulation } from "../../lib/lineageSimulation";
import { nodeTypes, edgeTypes, MINIMAP_NODE_COLOR } from "./graphConstants";
import { useGraphInteractions } from "../../hooks/useGraphInteractions";

function ViewportListener() {
  const setSemanticZoom = useGraphStore((s) => s.setSemanticZoom);
  const setZoomLevel = useUiStore((s) => s.setZoomLevel);
  const selectedNodeId = useGraphStore((s) => s.selectedNodeId);
  const rightPanelMode = useUiStore((s) => s.rightPanelMode);
  const saveViewport = useUiStore((s) => s.saveViewport);
  const { focusNode } = useGraphInteractions();

  useEffect(() => {
    if (selectedNodeId) {
      focusNode(selectedNodeId, 300);
    }
  }, [selectedNodeId, rightPanelMode, focusNode]);

  const onViewportChange = useCallback(
    (viewport: Viewport) => {
      setZoomLevel(viewport.zoom);
      if (viewport.zoom < 0.25) {
        setSemanticZoom("micro");
      } else if (viewport.zoom < 0.45) {
        setSemanticZoom("mini");
      } else {
        setSemanticZoom("normal");
      }
    },
    [setZoomLevel, setSemanticZoom],
  );

  const onViewportEnd = useCallback(
    (viewport: Viewport) => {
      saveViewport("lineage", viewport);
    },
    [saveViewport],
  );

  useOnViewportChange({
    onChange: onViewportChange,
    onEnd: onViewportEnd,
  });

  return null;
}

export function DagCanvas() {
  const lineageRfNodes = useGraphStore((s) => s.lineageRfNodes);
  const lineageRfEdges = useGraphStore((s) => s.lineageRfEdges);
  const rfNodes = useGraphStore((s) => s.rfNodes);
  const rfEdges = useGraphStore((s) => s.rfEdges);
  const setLineagePositions = useGraphStore((s) => s.setLineagePositions);
  const selectNode = useGraphStore((s) => s.selectNode);
  const selectedNodeId = useGraphStore((s) => s.selectedNodeId);
  const openDetailPanel = useUiStore((s) => s.openDetailPanel);
  const savedViewport = useUiStore((s) => s.viewports.lineage);
  const showMiniMap = rfNodes.length > 20;
  const simulationRef = useRef<ReturnType<typeof createLineageSimulation> | null>(
    null,
  );
  const pendingPositionsRef = useRef<Record<string, { x: number; y: number }> | null>(null);
  const rafIdRef = useRef<number>(0);

  const graphSignature = useMemo(() => {
    const nodePart = lineageRfNodes.map((node) => node.id).join("|");
    const edgePart = lineageRfEdges.map((edge) => edge.id).join("|");
    return `${nodePart}::${edgePart}`;
  }, [lineageRfEdges, lineageRfNodes]);
  const topologyNodes = useMemo(() => lineageRfNodes, [lineageRfNodes]);
  const topologyEdges = useMemo(() => lineageRfEdges, [lineageRfEdges]);

  const nodesWithSelection = useMemo(() => {
    return rfNodes.map((node) => ({
      ...node,
      selected: node.id === selectedNodeId,
      style: {
        ...node.style,
        cursor: "grab",
        transition:
          "box-shadow var(--transition-fast), filter var(--transition-fast)",
        boxShadow:
          node.id === selectedNodeId
            ? "0 0 0 1px rgba(46, 124, 246, 0.18), var(--shadow-md)"
            : node.style?.boxShadow,
      },
    }));
  }, [rfNodes, selectedNodeId]);

  useEffect(() => {
    if (topologyNodes.length === 0) {
      simulationRef.current?.stop();
      simulationRef.current = null;
      return;
    }

    const simulation = createLineageSimulation(topologyNodes, topologyEdges);
    simulationRef.current = simulation;
    simulation.on("tick", () => {
      pendingPositionsRef.current = Object.fromEntries(
        simulation.nodes().map((node) => [
          node.id,
          {
            x: node.x ?? 0,
            y: node.y ?? 0,
          },
        ]),
      );
      if (!rafIdRef.current) {
        rafIdRef.current = requestAnimationFrame(() => {
          rafIdRef.current = 0;
          if (pendingPositionsRef.current) {
            setLineagePositions(pendingPositionsRef.current);
            pendingPositionsRef.current = null;
          }
        });
      }
    });

    return () => {
      simulation.stop();
      if (rafIdRef.current) {
        cancelAnimationFrame(rafIdRef.current);
        rafIdRef.current = 0;
      }
      if (simulationRef.current === simulation) {
        simulationRef.current = null;
      }
    };
  }, [graphSignature, setLineagePositions, topologyEdges, topologyNodes]);

  const updateDraggedNode = (node: FlowNode) => {
    const simulationNode = simulationRef.current
      ?.nodes()
      .find((item) => item.id === node.id);
    if (!simulationNode) {
      return;
    }
    simulationNode.fx = node.position.x;
    simulationNode.fy = node.position.y;
    simulationNode.x = node.position.x;
    simulationNode.y = node.position.y;
    setLineagePositions({
      [node.id]: {
        x: node.position.x,
        y: node.position.y,
      },
    });
  };

  return (
    <ReactFlow
      nodes={nodesWithSelection}
      edges={rfEdges}
      nodeTypes={nodeTypes}
      edgeTypes={edgeTypes}
      defaultViewport={savedViewport}
      minZoom={0.2}
      maxZoom={2}
      panOnDrag
      zoomOnScroll
      selectionOnDrag={false}
      onNodeClick={(_e, node) => {
        selectNode(node.id);
        openDetailPanel(node.id);
      }}
      onNodeDragStart={(_event, node) => {
        updateDraggedNode(node);
        simulationRef.current?.alphaTarget(0.16).restart();
      }}
      onNodeDrag={(_event, node) => {
        updateDraggedNode(node);
      }}
      onNodeDragStop={(_event, node) => {
        const simulationNode = simulationRef.current
          ?.nodes()
          .find((item) => item.id === node.id);
        if (!simulationNode || !simulationRef.current) {
          return;
        }
        simulationNode.fx = null;
        simulationNode.fy = null;
        simulationRef.current.alpha(0.38).alphaTarget(0).restart();
      }}
      onPaneClick={() => {
        selectNode(null);
        useUiStore.getState().closeRightPanel();
      }}
    >
      <ViewportListener />
      <Background
        variant={BackgroundVariant.Dots}
        gap={20}
        size={1}
        color="var(--color-border-default)"
      />
      {showMiniMap && (
        <MiniMap
          nodeColor={MINIMAP_NODE_COLOR}
          maskColor="rgba(255, 255, 255, 0.7)"
          className="rounded-md"
        />
      )}
    </ReactFlow>
  );
}
