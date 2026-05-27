import { create } from "zustand";
import type { Node, Edge } from "@xyflow/react";
import type {
  AssociationInfo,
  GraphEdgeDTO,
  GraphNodeDTO,
} from "../api/types";
import {
  fetchLineage,
  fetchChildren,
  fetchAssociations,
  fetchRhizomes,
  fetchTopologyContext,
} from "../api/nodes";
import { toRfNode } from "../lib/mapping";
import { buildGraphViews } from "../lib/graphView";
import { useUiStore } from "./uiStore";

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
  expandingNodeIds: Set<string>;
  pendingFocusNodeId: string | null;

  selectedNodeId: string | null;
  rootNodeId: string | null;
  lineageRequestId: number;
  semanticZoom: SemanticZoom;

  loadRhizomes: () => Promise<void>;
  loadLineage: (nodeId: string, maxDepth?: number) => Promise<void>;
  loadChildren: (nodeId: string) => Promise<void>;
  loadTopologyContext: (
    nodeId: string,
    options?: {
      lineageDepth?: number;
      childrenDepth?: number;
    },
  ) => Promise<void>;
  loadAssociations: (nodeId: string) => Promise<void>;
  selectNode: (nodeId: string | null) => void;
  setSemanticZoom: (zoom: SemanticZoom) => void;
  expandChildren: (nodeId: string) => Promise<void>;
  getBoundaryNodeIds: () => string[];
  requestFocusNode: (nodeId: string) => void;
  clearPendingFocus: () => void;

  addNode: (node: GraphNodeDTO) => void;
  addEdge: (edge: GraphEdgeDTO) => void;
  removeEdgesBySourceAndType: (source: string, type: string) => void;
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
  expandingNodeIds: new Set<string>(),
  pendingFocusNodeId: null,
  selectedNodeId: null,
  rootNodeId: null,
  lineageRequestId: 0,
  semanticZoom: "normal" as SemanticZoom,

  loadRhizomes: async () => {
    try {
      const rhizomes = await fetchRhizomes(50);
      set({ rhizomes });
    } catch (e) {
      useUiStore.getState().addToast({
        type: "error",
        message: e instanceof Error ? e.message : "Failed to load rhizomes",
      });
    }
  },

  loadLineage: async (nodeId, maxDepth = 3) => {
    const requestId = get().lineageRequestId + 1;
    set({ lineageRequestId: requestId });

    try {
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
    } catch (e) {
      useUiStore.getState().addToast({
        type: "error",
        message: e instanceof Error ? e.message : "Failed to load graph lineage",
      });
    }
  },

  loadChildren: async (nodeId) => {
    try {
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
    } catch (e) {
      useUiStore.getState().addToast({
        type: "error",
        message: e instanceof Error ? e.message : "Failed to load children",
      });
    }
  },

  /**
   * 一次 HTTP 拉回 lineage + children 后用一次 set 完成视图建模——等价于
   * `loadLineage` 后串行 `loadChildren` 的最终状态，但少一次网络往返。
   *
   * 沿用 `loadLineage` 的 `lineageRequestId` 节流机制；如果聚合请求返回时已经
   * 有更新的 lineage 请求在路上，丢弃旧响应。
   */
  loadTopologyContext: async (nodeId, options) => {
    const requestId = get().lineageRequestId + 1;
    set({ lineageRequestId: requestId });

    try {
      const ctx = await fetchTopologyContext(nodeId, {
        lineageDepth: options?.lineageDepth ?? 3,
        childrenDepth: options?.childrenDepth ?? 2,
      });
      if (get().lineageRequestId !== requestId) return;

      const nodesMap = new Map<string, GraphNodeDTO>();
      ctx.lineage.nodes.forEach((n) => nodesMap.set(n.node_id, n));
      // children 可以与 lineage 共享根节点；后写覆盖即可，DTO 是等价的。
      ctx.children.nodes.forEach((n) => nodesMap.set(n.node_id, n));

      const edgeMap = new Map<string, GraphEdgeDTO>();
      for (const e of ctx.lineage.edges) {
        edgeMap.set(`${e.source}-${e.type}-${e.target}`, e);
      }
      for (const e of ctx.children.edges) {
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
        rootNodeId: nodeId,
      });
    } catch (e) {
      useUiStore.getState().addToast({
        type: "error",
        message:
          e instanceof Error ? e.message : "Failed to load graph context",
      });
    }
  },

  loadAssociations: async (nodeId) => {
    try {
      const associations = await fetchAssociations(nodeId);
      set({ associations });
    } catch (e) {
      useUiStore.getState().addToast({
        type: "error",
        message: e instanceof Error ? e.message : "Failed to load associations",
      });
    }
  },

  selectNode: (nodeId) => set({ selectedNodeId: nodeId }),

  setSemanticZoom: (zoom) => {
    if (get().semanticZoom !== zoom) {
      set({ semanticZoom: zoom });
    }
  },

  expandChildren: async (nodeId) => {
    const expanding = new Set(get().expandingNodeIds);
    if (expanding.has(nodeId)) return; // already expanding
    expanding.add(nodeId);
    set({ expandingNodeIds: expanding });

    try {
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

      set({ nodes: nodesMap, edges: allEdges });
      get().flushLayout();
    } catch (e) {
      useUiStore.getState().addToast({
        type: "error",
        message: e instanceof Error ? e.message : "Failed to expand children",
      });
    } finally {
      const next = new Set(get().expandingNodeIds);
      next.delete(nodeId);
      set({ expandingNodeIds: next });
    }
  },

  getBoundaryNodeIds: () => {
    const { nodes, edges, rootNodeId } = get();
    // Nodes that appear as target in edges but never as source
    const sourceIds = new Set(edges.map((e) => e.source));
    const targetIds = new Set(edges.map((e) => e.target));
    const boundary: string[] = [];
    for (const nodeId of nodes.keys()) {
      if (nodeId === rootNodeId) continue;
      if (targetIds.has(nodeId) && !sourceIds.has(nodeId)) {
        boundary.push(nodeId);
      }
    }
    return boundary;
  },

  requestFocusNode: (nodeId) => set({ pendingFocusNodeId: nodeId }),
  clearPendingFocus: () => set({ pendingFocusNodeId: null }),

  addNode: (node) => {
    const nodesMap = new Map(get().nodes);
    nodesMap.set(node.node_id, node);
    set({ nodes: nodesMap });
    // Defer relayout to addEdge — node alone has no position context
  },

  addEdge: (edge) => {
    set({ edges: [...get().edges, edge] });
  },

  removeEdgesBySourceAndType: (source, type) => {
    const filtered = get().edges.filter(
      (e) => !(e.source === source && e.type === type),
    );
    if (filtered.length !== get().edges.length) {
      set({ edges: filtered });
      get().scheduleFlushLayout();
    }
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
