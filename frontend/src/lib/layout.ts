import type { Edge, Node } from "@xyflow/react";
import dagre from "@dagrejs/dagre";

export type SemanticZoom = "micro" | "mini" | "normal";

export function getLayoutMetrics() {
  return {
    NODE_WIDTH: 280,
    NODE_HEIGHT: 120,
    NODE_SEP_X: 120,
    RANK_SEP_Y: 100,
  };
}

function resolveEdgeHandles(edge: Edge) {
  const relType = String(edge.data?.relType ?? "");

  // 统一所有连线由父节点下方垂发，汇入子节点上方。杜绝一切侧边侧漏产生的迂回飞线穿模问题。
  return {
    routeKind: relType === "CONTINUES_FROM" ? "continue" : "branch",
    branchSide: null,
    sourceHandle: "source-bottom",
    targetHandle: "target-top",
  };
}

export function applyTrackLayout(
  nodes: Node[],
  edges: Edge[],
): { nodes: Node[]; edges: Edge[] } {
  if (nodes.length === 0) {
    return { nodes, edges };
  }

  const { NODE_WIDTH, NODE_HEIGHT, NODE_SEP_X, RANK_SEP_Y } = getLayoutMetrics();

  const dagreGraph = new dagre.graphlib.Graph();
  dagreGraph.setDefaultEdgeLabel(() => ({}));

  dagreGraph.setGraph({
    rankdir: "TB",             // Top to Bottom
    nodesep: NODE_SEP_X,       // 同层节点间的最小距离
    ranksep: RANK_SEP_Y,       // 层级间的距离
    edgesep: 40,               // 边线之间的距离
    ranker: "network-simplex", // 使用网络单纯形求最佳交叉率
  });

  nodes.forEach((node) => {
    dagreGraph.setNode(node.id, { width: NODE_WIDTH, height: NODE_HEIGHT });
  });

  edges.forEach((edge) => {
    const relType = String(edge.data?.relType ?? "");
    // CONTINUES_FROM 作为主干继承，获得极高权重，使其垂直排布
    const weight = relType === "CONTINUES_FROM" ? 100 : 1;
    // minlen 用于控制枝条的跳层距，通常 1 就够了
    dagreGraph.setEdge(edge.source, edge.target, { weight, minlen: 1 });
  });

  dagre.layout(dagreGraph);

  const layoutNodes = nodes.map((node) => {
    const nodeWithPosition = dagreGraph.node(node.id);
    return {
      ...node,
      position: {
        x: nodeWithPosition.x - NODE_WIDTH / 2,
        y: nodeWithPosition.y - NODE_HEIGHT / 2,
      },
      data: {
        ...node.data,
      }
    };
  });

  const layoutEdges = edges.map((edge) => {
    const handles = resolveEdgeHandles(edge);
    return {
      ...edge,
      sourceHandle: handles.sourceHandle,
      targetHandle: handles.targetHandle,
      data: {
        ...edge.data,
        routeKind: handles.routeKind,
        branchSide: handles.branchSide,
      },
    };
  });

  return { nodes: layoutNodes, edges: layoutEdges };
}
