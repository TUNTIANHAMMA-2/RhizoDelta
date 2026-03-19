import {
  ReactFlow,
  Background,
  MiniMap,
  useOnViewportChange,
  type Viewport,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { useGraphStore } from "../../stores/graphStore";
import { useUiStore } from "../../stores/uiStore";
import { HumanPostNode } from "./HumanPostNode";
import { ConsensusNode } from "./ConsensusNode";
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

function ViewportListener() {
  const setSemanticZoom = useGraphStore((s) => s.setSemanticZoom);
  const setZoomLevel = useUiStore((s) => s.setZoomLevel);

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

export function DagCanvas() {
  const rfNodes = useGraphStore((s) => s.rfNodes);
  const rfEdges = useGraphStore((s) => s.rfEdges);
  const selectNode = useGraphStore((s) => s.selectNode);
  const openDetailPanel = useUiStore((s) => s.openDetailPanel);
  const showMiniMap = rfNodes.length > 20;

  return (
    <ReactFlow
      nodes={rfNodes}
      edges={rfEdges}
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
      onPaneClick={() => {
        selectNode(null);
        useUiStore.getState().closeRightPanel();
      }}
    >
      <ViewportListener />
      <Background
        variant={"dots" as any}
        gap={20}
        size={1}
        color="var(--color-border-default)"
      />
      {showMiniMap && (
        <MiniMap
          nodeColor={MINIMAP_NODE_COLOR}
          maskColor="rgba(250, 250, 248, 0.7)"
          style={{ borderRadius: 8 }}
        />
      )}
    </ReactFlow>
  );
}
