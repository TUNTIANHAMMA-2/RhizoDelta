import type { Edge, Node } from "@xyflow/react";
import type { GraphEdgeDTO, GraphNodeDTO } from "../api/types";
import { applyTrackLayout } from "./layout.ts";
import { toRfEdge, toRfNode } from "./mapping.ts";

type PositionMap = Map<string, { x: number; y: number }>;

export interface GraphFlowView {
  nodes: Node[];
  edges: Edge[];
}

export interface GraphViews {
  lineage: GraphFlowView;
  explore: GraphFlowView;
}

function clonePosition(position: { x: number; y: number }) {
  return { x: position.x, y: position.y };
}

function buildExploreBootstrap(
  lineage: GraphFlowView,
  priorPositions?: PositionMap,
): GraphFlowView {
  const edges: Edge[] = lineage.edges.map((edge) => ({
    ...edge,
    sourceHandle: "source-center",
    targetHandle: "target-center",
    data: {
      ...edge.data,
      viewMode: "explore",
      routeKind: "explore",
    },
  }));

  return {
    nodes: lineage.nodes.map((node) => ({
      ...node,
      position: clonePosition(priorPositions?.get(node.id) ?? node.position),
    })),
    edges,
  };
}

export function buildGraphViews(
  nodes: Iterable<GraphNodeDTO>,
  edges: GraphEdgeDTO[],
  priorExplorePositions?: PositionMap,
): GraphViews {
  const rawNodes = Array.from(nodes, toRfNode);
  const rawEdges = edges.map(toRfEdge);
  const lineage = applyTrackLayout(rawNodes, rawEdges);

  return {
    lineage,
    explore: buildExploreBootstrap(lineage, priorExplorePositions),
  };
}
