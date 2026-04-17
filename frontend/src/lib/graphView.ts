import type { Edge, Node } from "@xyflow/react";
import type { AssociationInfo, GraphEdgeDTO, GraphNodeDTO } from "../api/types";
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

export function associationToRfEdge(
  assoc: AssociationInfo,
  anchorNodeId: string,
): Edge {
  const source =
    assoc.direction === "OUTGOING"
      ? anchorNodeId
      : assoc.related_node.node_id;
  const target =
    assoc.direction === "OUTGOING"
      ? assoc.related_node.node_id
      : anchorNodeId;

  return {
    id: `assoc-${assoc.association_id}`,
    source,
    target,
    type: "association",
    sourceHandle: "source-center",
    targetHandle: "target-center",
    data: {
      associationType: assoc.type,
      confidence: assoc.confidence,
      createdAt: assoc.created_at,
    },
  };
}

function buildExploreBootstrap(
  lineage: GraphFlowView,
  priorPositions?: PositionMap,
  associations?: AssociationInfo[],
  anchorNodeId?: string,
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

  if (associations && anchorNodeId) {
    const nodeIds = new Set(lineage.nodes.map((n) => n.id));
    for (const assoc of associations) {
      const rfEdge = associationToRfEdge(assoc, anchorNodeId);
      // Only include edges whose both endpoints exist in the current graph
      if (nodeIds.has(rfEdge.source) && nodeIds.has(rfEdge.target)) {
        edges.push(rfEdge);
      }
    }
  }

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
  associations?: AssociationInfo[],
  anchorNodeId?: string,
): GraphViews {
  const rawNodes = Array.from(nodes, toRfNode);
  const rawEdges = edges.map(toRfEdge);
  const lineage = applyTrackLayout(rawNodes, rawEdges);

  return {
    lineage,
    explore: buildExploreBootstrap(
      lineage,
      priorExplorePositions,
      associations,
      anchorNodeId,
    ),
  };
}
