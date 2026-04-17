import {
  Background,
  MiniMap,
  ReactFlow,
  type Node as FlowNode,
  useOnViewportChange,
  useReactFlow,
  type Viewport,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { useCallback, useEffect, useMemo, useRef } from "react";
import { useGraphStore } from "../../stores/graphStore";
import { useUiStore } from "../../stores/uiStore";
import { createExploreSimulation } from "../../lib/exploreSimulation";
import { nodeTypes, edgeTypes, MINIMAP_NODE_COLOR } from "./graphConstants";
import { AssociationToggle } from "./AssociationToggle";

function ExploreViewportListener() {
  const { setCenter, getViewport } = useReactFlow();
  const setSemanticZoom = useGraphStore((s) => s.setSemanticZoom);
  const selectedNodeId = useGraphStore((s) => s.selectedNodeId);
  const rootNodeId = useGraphStore((s) => s.rootNodeId);
  const exploreRfNodes = useGraphStore((s) => s.exploreRfNodes);
  const setZoomLevel = useUiStore((s) => s.setZoomLevel);
  const rightPanelMode = useUiStore((s) => s.rightPanelMode);
  const saveViewport = useUiStore((s) => s.saveViewport);

  useEffect(() => {
    const focusNodeId = selectedNodeId ?? rootNodeId;
    if (!focusNodeId) {
      return;
    }
    const node = exploreRfNodes.find((item) => item.id === focusNodeId);
    if (!node) {
      return;
    }
    const timer = setTimeout(() => {
      const { zoom: currentZoom } = getViewport();
      setCenter(node.position.x, node.position.y, { zoom: Math.max(currentZoom, 0.8), duration: 600 });
    }, 300);
    return () => clearTimeout(timer);
  }, [exploreRfNodes, rootNodeId, selectedNodeId, rightPanelMode, setCenter, getViewport]);

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
      saveViewport("explore", viewport);
    },
    [saveViewport],
  );

  useOnViewportChange({
    onChange: onViewportChange,
    onEnd: onViewportEnd,
  });

  return null;
}

export function ExploreCanvas() {
  const exploreRfNodes = useGraphStore((s) => s.exploreRfNodes);
  const exploreRfEdges = useGraphStore((s) => s.exploreRfEdges);
  const rootNodeId = useGraphStore((s) => s.rootNodeId);
  const setExplorePositions = useGraphStore((s) => s.setExplorePositions);
  const selectNode = useGraphStore((s) => s.selectNode);
  const selectedNodeId = useGraphStore((s) => s.selectedNodeId);
  const showAssociations = useGraphStore((s) => s.showAssociations);
  const associationRfEdges = useGraphStore((s) => s.associationRfEdges);
  const openDetailPanel = useUiStore((s) => s.openDetailPanel);
  const savedViewport = useUiStore((s) => s.viewports.explore);
  const showMiniMap = exploreRfNodes.length > 20;
  const simulationRef = useRef<ReturnType<typeof createExploreSimulation> | null>(
    null,
  );
  const pendingPositionsRef = useRef<Record<string, { x: number; y: number }> | null>(null);
  const rafIdRef = useRef<number>(0);

  const graphSignature = useMemo(() => {
    const nodePart = exploreRfNodes.map((node) => node.id).join("|");
    const edgePart = exploreRfEdges.map((edge) => edge.id).join("|");
    return `${nodePart}::${edgePart}`;
  }, [exploreRfEdges, exploreRfNodes]);
  const topologyNodes = useMemo(() => exploreRfNodes, [graphSignature]);
  const topologyEdges = useMemo(() => exploreRfEdges, [graphSignature]);
  const anchorNodeId = selectedNodeId ?? rootNodeId ?? exploreRfNodes[0]?.id ?? null;

  const nodesWithSelection = useMemo(
    () =>
      exploreRfNodes.map((node) => ({
        ...node,
        selected: node.id === selectedNodeId,
      })),
    [exploreRfNodes, selectedNodeId],
  );

  const mergedEdges = useMemo(
    () =>
      showAssociations
        ? [...exploreRfEdges, ...associationRfEdges]
        : exploreRfEdges,
    [exploreRfEdges, associationRfEdges, showAssociations],
  );

  useEffect(() => {
    if (!anchorNodeId || exploreRfNodes.length === 0) {
      simulationRef.current?.stop();
      simulationRef.current = null;
      return;
    }

    const simulation = createExploreSimulation(
      topologyNodes,
      topologyEdges,
      anchorNodeId,
    );
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
            setExplorePositions(pendingPositionsRef.current);
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
  }, [anchorNodeId, graphSignature, setExplorePositions, topologyEdges, topologyNodes]);

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
    setExplorePositions({
      [node.id]: {
        x: node.position.x,
        y: node.position.y,
      },
    });
  };

  return (
    <ReactFlow
      nodes={nodesWithSelection}
      edges={mergedEdges}
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
        simulationRef.current?.alphaTarget(0.12).restart();
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
        simulationRef.current.alpha(0.35).alphaTarget(0).restart();
      }}
      onPaneClick={() => {
        selectNode(null);
        useUiStore.getState().closeRightPanel();
      }}
    >
      <ExploreViewportListener />
      <AssociationToggle />
      <Background
        variant={"dots" as never}
        gap={20}
        size={1}
        color="var(--color-border-default)"
      />
      {showMiniMap && (
        <MiniMap
          nodeColor={MINIMAP_NODE_COLOR}
          maskColor="rgba(255, 255, 255, 0.7)"
          style={{ borderRadius: 8 }}
        />
      )}
    </ReactFlow>
  );
}
