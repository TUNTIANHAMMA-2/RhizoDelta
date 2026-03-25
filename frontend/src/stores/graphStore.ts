import { create } from "zustand";
import type { Node, Edge } from "@xyflow/react";
import type {
  AssociationInfo,
  GraphEdgeDTO,
  GraphNodeDTO,
} from "../api/types";
import { fetchLineage, fetchChildren, fetchAssociations, fetchRhizomes } from "../api/nodes";
import { toRfNode } from "../lib/mapping";
import { buildGraphViews } from "../lib/graphView";

export type SemanticZoom = "micro" | "mini" | "normal";

const LAYOUT_FLUSH_DELAY = 50;
let _flushTimer: ReturnType<typeof setTimeout> | null = null;

export interface GraphState {
  nodes: Map<string, GraphNodeDTO>;
  edges: GraphEdgeDTO[];
  associations: AssociationInfo[];
  rhizomes: GraphNodeDTO[];
  lineageRfNodes: Node[];
  lineageRfEdges: Edge[];
  exploreRfNodes: Node[];
  exploreRfEdges: Edge[];
  rfNodes: Node[];
  rfEdges: Edge[];

  selectedNodeId: string | null;
  rootNodeId: string | null;
  lineageRequestId: number;
  semanticZoom: SemanticZoom;

  loadRhizomes: () => Promise<void>;
  loadLineage: (nodeId: string, maxDepth?: number) => Promise<void>;
  loadChildren: (nodeId: string) => Promise<void>;
  loadAssociations: (nodeId: string) => Promise<void>;
  selectNode: (nodeId: string | null) => void;
  setSemanticZoom: (zoom: SemanticZoom) => void;

  addNode: (node: GraphNodeDTO) => void;
  addEdge: (edge: GraphEdgeDTO) => void;
  flushLayout: () => void;
  scheduleFlushLayout: () => void;
  setLineagePositions: (
    positions: Record<string, { x: number; y: number }>,
  ) => void;
  setExplorePositions: (
    positions: Record<string, { x: number; y: number }>,
  ) => void;

  // Optimistic UI
  addOptimisticNode: (tempId: string, position: { x: number; y: number }, label: string) => void;
  resolveOptimisticNode: (tempId: string, realNode: GraphNodeDTO) => void;
}

export const useGraphStore = create<GraphState>((set, get) => ({
  nodes: new Map(),
  edges: [],
  associations: [],
  rhizomes: [],
  lineageRfNodes: [],
  lineageRfEdges: [],
  exploreRfNodes: [],
  exploreRfEdges: [],
  rfNodes: [],
  rfEdges: [],
  selectedNodeId: null,
  rootNodeId: null,
  lineageRequestId: 0,
  semanticZoom: "normal" as SemanticZoom,

  loadRhizomes: async () => {
    const rhizomes = await fetchRhizomes(50);
    set({ rhizomes });
  },

  loadLineage: async (nodeId, maxDepth = 3) => {
    const requestId = get().lineageRequestId + 1;
    set({ lineageRequestId: requestId });

    const topo = await fetchLineage(nodeId, maxDepth);

    // Discard stale response if a newer request was issued
    if (get().lineageRequestId !== requestId) return;

    const nodesMap = new Map<string, GraphNodeDTO>();
    topo.nodes.forEach((n) => nodesMap.set(n.node_id, n));

    const priorExplorePositions = new Map(
      get().exploreRfNodes.map((node) => [node.id, node.position]),
    );
    const views = buildGraphViews(
      topo.nodes,
      topo.edges,
      priorExplorePositions,
    );

    set({
      nodes: nodesMap,
      edges: topo.edges,
      lineageRfNodes: views.lineage.nodes,
      lineageRfEdges: views.lineage.edges,
      exploreRfNodes: views.explore.nodes,
      exploreRfEdges: views.explore.edges,
      rfNodes: views.lineage.nodes,
      rfEdges: views.lineage.edges,
      rootNodeId: nodeId,
    });
  },

  loadChildren: async (nodeId) => {
    const topo = await fetchChildren(nodeId, 2);
    const nodesMap = new Map(get().nodes);
    topo.nodes.forEach((n) => nodesMap.set(n.node_id, n));

    const edgeMap = new Map(
      get().edges.map((e) => [`${e.source}-${e.type}-${e.target}`, e]),
    );
    for (const e of topo.edges) {
      edgeMap.set(`${e.source}-${e.type}-${e.target}`, e);
    }
    const allEdges = [...edgeMap.values()];
    const priorExplorePositions = new Map(
      get().exploreRfNodes.map((node) => [node.id, node.position]),
    );
    const views = buildGraphViews(
      nodesMap.values(),
      allEdges,
      priorExplorePositions,
    );

    set({
      nodes: nodesMap,
      edges: allEdges,
      lineageRfNodes: views.lineage.nodes,
      lineageRfEdges: views.lineage.edges,
      exploreRfNodes: views.explore.nodes,
      exploreRfEdges: views.explore.edges,
      rfNodes: views.lineage.nodes,
      rfEdges: views.lineage.edges,
    });
  },

  loadAssociations: async (nodeId) => {
    const associations = await fetchAssociations(nodeId);
    set({ associations });
  },

  selectNode: (nodeId) => set({ selectedNodeId: nodeId }),

  setSemanticZoom: (zoom) => {
    if (get().semanticZoom !== zoom) {
      set({ semanticZoom: zoom });
    }
  },

  addNode: (node) => {
    const nodesMap = new Map(get().nodes);
    nodesMap.set(node.node_id, node);
    set({ nodes: nodesMap });
    // Defer relayout to addEdge — node alone has no position context
  },

  addEdge: (edge) => {
    set({ edges: [...get().edges, edge] });
  },

  flushLayout: () => {
    const nodesMap = get().nodes;
    const allEdges = get().edges;
    const priorExplorePositions = new Map(
      get().exploreRfNodes.map((node) => [node.id, node.position]),
    );

    const views = buildGraphViews(
      nodesMap.values(),
      allEdges,
      priorExplorePositions,
    );
    set({
      lineageRfNodes: views.lineage.nodes,
      lineageRfEdges: views.lineage.edges,
      exploreRfNodes: views.explore.nodes,
      exploreRfEdges: views.explore.edges,
      rfNodes: views.lineage.nodes,
      rfEdges: views.lineage.edges,
    });
  },

  scheduleFlushLayout: () => {
    if (_flushTimer) clearTimeout(_flushTimer);
    _flushTimer = setTimeout(() => {
      _flushTimer = null;
      get().flushLayout();
    }, LAYOUT_FLUSH_DELAY);
  },

  setLineagePositions: (positions) => {
    let changed = false;
    const nextLineageRfNodes = get().rfNodes.map((node) => {
      const position = positions[node.id];
      if (!position) {
        return node;
      }
      const dx = position.x - node.position.x;
      const dy = position.y - node.position.y;
      if (Math.abs(dx) < 0.5 && Math.abs(dy) < 0.5) {
        return node;
      }
      changed = true;
      return {
        ...node,
        position: { x: position.x, y: position.y },
      };
    });
    if (changed) {
      set({ rfNodes: nextLineageRfNodes });
    }
  },

  setExplorePositions: (positions) => {
    let changed = false;
    const nextExploreRfNodes = get().exploreRfNodes.map((node) => {
      const position = positions[node.id];
      if (!position) {
        return node;
      }
      const dx = position.x - node.position.x;
      const dy = position.y - node.position.y;
      if (Math.abs(dx) < 0.5 && Math.abs(dy) < 0.5) {
        return node;
      }
      changed = true;
      return {
        ...node,
        position: { x: position.x, y: position.y },
      };
    });
    if (changed) {
      set({ exploreRfNodes: nextExploreRfNodes });
    }
  },

  addOptimisticNode: (tempId, position, label) => {
    const rfNode: Node = {
      id: tempId,
      type: label === "AI_Consensus" ? "consensus" : label === "Result" ? "result" : "humanPost",
      position,
      data: {
        node_id: tempId,
        label,
        content: null,
        summary_content: null,
        author_id: null,
        agent_version: null,
        operation_id: null,
        created_at: new Date().toISOString(),
        has_embedding: false,
        isOptimistic: true,
      },
    };
    set({ rfNodes: [...get().rfNodes, rfNode] });
  },

  resolveOptimisticNode: (tempId, realNode) => {
    const nodesMap = new Map(get().nodes);
    nodesMap.set(realNode.node_id, realNode);
    const rfNodes = get().rfNodes.map((n) =>
      n.id === tempId
        ? { ...toRfNode(realNode), position: n.position }
        : n,
    );
    set({ nodes: nodesMap, rfNodes });
  },
}));
