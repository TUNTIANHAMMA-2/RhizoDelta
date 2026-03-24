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
import { useEffect, useMemo, useRef } from "react";
import { useGraphStore } from "../../stores/graphStore";
import { useUiStore } from "../../stores/uiStore";
import { createExploreSimulation } from "../../lib/exploreSimulation";
import { ConsensusNode } from "./ConsensusNode";
import { HumanPostNode } from "./HumanPostNode";
import { ResultNode } from "./ResultNode";
import { VersionEdge } from "./VersionEdge";

const nodeTypes = {
  humanPost: HumanPostNode,
  consensus: ConsensusNode,
  result: ResultNode,
};

const edgeTypes = {
  versionEdge: VersionEdge,
};

const MINIMAP_NODE_COLOR = (node: { type?: string }) => {
  switch (node.type) {
    case "humanPost":
      return "#2E7CF6";
    case "consensus":
      return "#9B59B6";
    case "result":
      return "#0D9488";
    default:
      return "#B4B4B0";
  }
};

function ExploreViewportListener() {
  const { setCenter } = useReactFlow();
  const setSemanticZoom = useGraphStore((s) => s.setSemanticZoom);
  const selectedNodeId = useGraphStore((s) => s.selectedNodeId);
  const rootNodeId = useGraphStore((s) => s.rootNodeId);
  const exploreRfNodes = useGraphStore((s) => s.exploreRfNodes);
  const setZoomLevel = useUiStore((s) => s.setZoomLevel);

  useEffect(() => {
    const focusNodeId = selectedNodeId ?? rootNodeId;
    if (!focusNodeId) {
      return;
    }
    const node = exploreRfNodes.find((item) => item.id === focusNodeId);
    if (!node) {
      return;
    }
    setCenter(node.position.x, node.position.y, { zoom: 1, duration: 600 });
  }, [exploreRfNodes, rootNodeId, selectedNodeId, setCenter]);

  useOnViewportChange({
    onChange: (viewport: Viewport) => {
      setZoomLevel(viewport.zoom);
      if (viewport.zoom < 0.5) {
        setSemanticZoom("micro");
      } else if (viewport.zoom < 0.8) {
        setSemanticZoom("mini");
      } else {
        setSemanticZoom("normal");
      }
    },
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
  const openDetailPanel = useUiStore((s) => s.openDetailPanel);
  const showMiniMap = exploreRfNodes.length > 20;
  const simulationRef = useRef<ReturnType<typeof createExploreSimulation> | null>(
    null,
  );
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
      const positions = Object.fromEntries(
        simulation.nodes().map((node) => [
          node.id,
          {
            x: node.x ?? 0,
            y: node.y ?? 0,
          },
        ]),
      );
      setExplorePositions(positions);
    });

    return () => {
      simulation.stop();
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
      edges={exploreRfEdges}
      nodeTypes={nodeTypes}
      edgeTypes={edgeTypes}
      defaultViewport={{ x: 0, y: 0, zoom: 1 }}
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
      <Background
        variant={"dots" as never}
        gap={20}
        size={1}
        color="var(--color-border-default)"
      />
      {showMiniMap && (
        <MiniMap
          nodeColor={MINIMAP_NODE_COLOR}
          maskColor="rgba(252, 249, 242, 0.7)"
          style={{ borderRadius: 8 }}
        />
      )}
    </ReactFlow>
  );
}
