import { create } from "zustand";
import type { Node, Edge } from "@xyflow/react";
import type {
  AssociationInfo,
  GraphEdgeDTO,
  GraphNodeDTO,
} from "../api/types";
import { fetchLineage, fetchChildren, fetchAssociations } from "../api/nodes";
import { applyDagreLayout } from "../lib/dagre";
import { toRfNode, toRfEdge } from "../lib/mapping";

export type SemanticZoom = "micro" | "mini" | "normal";

export interface GraphState {
  nodes: Map<string, GraphNodeDTO>;
  edges: GraphEdgeDTO[];
  associations: AssociationInfo[];
  rfNodes: Node[];
  rfEdges: Edge[];

  selectedNodeId: string | null;
  rootNodeId: string | null;
  semanticZoom: SemanticZoom;

  loadLineage: (nodeId: string, maxDepth?: number) => Promise<void>;
  loadChildren: (nodeId: string) => Promise<void>;
  loadAssociations: (nodeId: string) => Promise<void>;
  selectNode: (nodeId: string | null) => void;
  setSemanticZoom: (zoom: SemanticZoom) => void;

  addNode: (node: GraphNodeDTO) => void;
  addEdge: (edge: GraphEdgeDTO) => void;
}

export const useGraphStore = create<GraphState>((set, get) => ({
  nodes: new Map(),
  edges: [],
  associations: [],
  rfNodes: [],
  rfEdges: [],
  selectedNodeId: null,
  rootNodeId: null,
  semanticZoom: "normal" as SemanticZoom,

  loadLineage: async (nodeId, maxDepth = 3) => {
    const topo = await fetchLineage(nodeId, maxDepth);
    const nodesMap = new Map<string, GraphNodeDTO>();
    topo.nodes.forEach((n) => nodesMap.set(n.node_id, n));

    const rawRfNodes = topo.nodes.map(toRfNode);
    const rawRfEdges = topo.edges.map(toRfEdge);
    const { nodes: layoutNodes, edges: layoutEdges } = applyDagreLayout(
      rawRfNodes,
      rawRfEdges,
    );

    set({
      nodes: nodesMap,
      edges: topo.edges,
      rfNodes: layoutNodes,
      rfEdges: layoutEdges,
      rootNodeId: nodeId,
    });
  },

  loadChildren: async (nodeId) => {
    const topo = await fetchChildren(nodeId, 2);
    const nodesMap = new Map(get().nodes);
    topo.nodes.forEach((n) => nodesMap.set(n.node_id, n));

    const allEdges = [...get().edges, ...topo.edges];
    const rawRfNodes = Array.from(nodesMap.values()).map(toRfNode);
    const rawRfEdges = allEdges.map(toRfEdge);
    const { nodes: layoutNodes, edges: layoutEdges } = applyDagreLayout(
      rawRfNodes,
      rawRfEdges,
    );

    set({
      nodes: nodesMap,
      edges: allEdges,
      rfNodes: layoutNodes,
      rfEdges: layoutEdges,
    });
  },

  loadAssociations: async (nodeId) => {
    const associations = await fetchAssociations(nodeId);
    set({ associations });
  },

  selectNode: (nodeId) => set({ selectedNodeId: nodeId }),

  setSemanticZoom: (zoom) => {
    if (get().semanticZoom !== zoom) set({ semanticZoom: zoom });
  },

  addNode: (node) => {
    const nodesMap = new Map(get().nodes);
    nodesMap.set(node.node_id, node);
    const rfNode = toRfNode(node);
    // Place new node below the last node as a simple fallback
    const lastNode = get().rfNodes[get().rfNodes.length - 1];
    if (lastNode) {
      rfNode.position = {
        x: lastNode.position.x,
        y: lastNode.position.y + 120,
      };
    }
    set({
      nodes: nodesMap,
      rfNodes: [...get().rfNodes, rfNode],
    });
  },

  addEdge: (edge) => {
    const rfEdge = toRfEdge(edge);
    set({
      edges: [...get().edges, edge],
      rfEdges: [...get().rfEdges, rfEdge],
    });
  },
}));
