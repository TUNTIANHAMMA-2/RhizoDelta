import type { Edge, Node } from "@xyflow/react";

export type SemanticZoom = "micro" | "mini" | "normal";

export function getLayoutMetrics() {
  return {
    NODE_WIDTH: 280,
    NODE_HEIGHT: 120,
    NODE_SEP_X: 220,
    RANK_SEP_Y: 100,
  };
}

const ROOT_LANE_GAP = 4;

const edgePriority = (type?: string) => {
  switch (type) {
    case "CONTINUES_FROM":
      return 1;
    case "MATERIALIZED_FROM":
      return 2;
    case "MERGED_INTO":
      return 3;
    case "CONVERGED_FROM":
      return 4;
    case "CROSS_SYNTHESIZED_FROM":
      return 5;
    case "BRANCHED_FROM":
      return 6;
    default:
      return 99;
  }
};

const isBranchEdge = (edge: Edge) => edge.data?.relType === "BRANCHED_FROM";

const toTimestamp = (edge: Edge) => {
  const createdAt = edge.data?.createdAt;
  return createdAt ? new Date(String(createdAt)).getTime() : 0;
};

const sortEdges = (edges: Edge[]) =>
  [...edges].sort((left, right) => {
    const priorityDiff =
      edgePriority(String(left.data?.relType)) -
      edgePriority(String(right.data?.relType));
    if (priorityDiff !== 0) {
      return priorityDiff;
    }

    const timestampDiff = toTimestamp(left) - toTimestamp(right);
    if (timestampDiff !== 0) {
      return timestampDiff;
    }

    return left.source.localeCompare(right.source);
  });

const branchOffset = (index: number) => {
  const step = Math.floor(index / 2) + 1;
  return index % 2 === 0 ? step : -step;
};

function computeRanks(
  nodes: Node[],
  inEdgesMap: Map<string, Edge[]>,
  outEdgesMap: Map<string, Edge[]>,
) {
  const ranks = new Map<string, number>();
  const outDegree = new Map<string, number>();
  nodes.forEach((node) => {
    ranks.set(node.id, 0);
    outDegree.set(node.id, outEdgesMap.get(node.id)?.length ?? 0);
  });

  const queue = nodes
    .filter((node) => outDegree.get(node.id) === 0)
    .map((node) => node.id);
  const topoOrder: string[] = [];

  while (queue.length > 0) {
    const currentNodeId = queue.shift()!;
    topoOrder.push(currentNodeId);
    const currentRank = ranks.get(currentNodeId) ?? 0;

    for (const edge of inEdgesMap.get(currentNodeId) ?? []) {
      const childId = edge.source;
      const rankIncrement = isBranchEdge(edge) ? 0 : 1;
      ranks.set(childId, Math.max(ranks.get(childId) ?? 0, currentRank + rankIncrement));
      outDegree.set(childId, (outDegree.get(childId) ?? 1) - 1);
      if (outDegree.get(childId) === 0) {
        queue.push(childId);
      }
    }
  }

  for (const node of nodes) {
    if (!topoOrder.includes(node.id)) {
      topoOrder.push(node.id);
      if (!ranks.has(node.id)) {
        ranks.set(node.id, 0);
      }
    }
  }

  return { ranks, topoOrder };
}

function resolvePrimaryParents(nodes: Node[], outEdgesMap: Map<string, Edge[]>) {
  const primaryParent = new Map<string, string>();

  for (const node of nodes) {
    const sortedParents = sortEdges(outEdgesMap.get(node.id) ?? []);
    if (sortedParents.length > 0) {
      primaryParent.set(node.id, sortedParents[0].target);
    }
  }

  return primaryParent;
}

function buildPrimaryChildren(edges: Edge[], primaryParent: Map<string, string>) {
  const primaryChildren = new Map<string, Edge[]>();

  for (const edge of edges) {
    if (primaryParent.get(edge.source) !== edge.target) {
      continue;
    }
    const children = primaryChildren.get(edge.target) ?? [];
    children.push(edge);
    primaryChildren.set(edge.target, children);
  }

  for (const [nodeId, nodeEdges] of primaryChildren.entries()) {
    primaryChildren.set(nodeId, sortEdges(nodeEdges));
  }

  return primaryChildren;
}

function assignLanes(
  nodes: Node[],
  outEdgesMap: Map<string, Edge[]>,
  primaryChildren: Map<string, Edge[]>,
  ranks: Map<string, number>,
) {
  const lanes = new Map<string, number>();
  const occupied = new Set<string>();
  const visited = new Set<string>();
  const rootIds = nodes
    .filter((node) => (outEdgesMap.get(node.id)?.length ?? 0) === 0)
    .map((node) => node.id);

  const visitNode = (nodeId: string, preferredLane: number) => {
    if (visited.has(nodeId)) {
      return;
    }

    visited.add(nodeId);

    const rank = ranks.get(nodeId) ?? 0;
    let lane = preferredLane;
    
    // Collision avoidance: find the nearest free lane at this rank
    let offset = 1;
    let sign = lane >= 0 ? 1 : -1;
    while (occupied.has(`${rank},${lane}`)) {
      lane = preferredLane + sign * offset;
      if (sign === 1 && preferredLane >= 0) { sign = -1; }
      else if (sign === -1 && preferredLane >= 0) { sign = 1; offset++; }
      else if (sign === -1 && preferredLane < 0) { sign = 1; }
      else { sign = -1; offset++; }
    }
    
    occupied.add(`${rank},${lane}`);
    lanes.set(nodeId, lane);

    const children = primaryChildren.get(nodeId) ?? [];
    const continuationChildren = children.filter((edge) => !isBranchEdge(edge));
    const branchChildren = children.filter(isBranchEdge);
    const trunkChild = continuationChildren.shift();

    if (trunkChild) {
      visitNode(trunkChild.source, lane);
    }

    const sideChildren = [...branchChildren, ...continuationChildren];
    sideChildren.forEach((edge, index) => {
      visitNode(edge.source, lane + branchOffset(index));
    });
  };

  rootIds.forEach((rootId, index) => {
    visitNode(rootId, index * ROOT_LANE_GAP);
  });

  let fallbackLane = rootIds.length * ROOT_LANE_GAP;
  nodes.forEach((node) => {
    if (!lanes.has(node.id)) {
      visitNode(node.id, fallbackLane);
      fallbackLane += 1;
    }
  });

  return lanes;
}

function resolveEdgeHandles(
  edge: Edge,
  nodePositions: Map<string, { x: number; y: number }>,
) {
  const relType = String(edge.data?.relType ?? "");
  const sourcePosition = nodePositions.get(edge.source);
  const targetPosition = nodePositions.get(edge.target);

  if (relType === "BRANCHED_FROM" && sourcePosition && targetPosition) {
    const branchOnLeft = sourcePosition.x < targetPosition.x;
    return {
      routeKind: "branch",
      branchSide: branchOnLeft ? "left" : "right",
      sourceHandle: branchOnLeft ? "source-right" : "source-left",
      targetHandle: branchOnLeft ? "target-left" : "target-right",
    };
  }

  if (relType === "CONTINUES_FROM") {
    return {
      routeKind: "continue",
      branchSide: null,
      sourceHandle: "source-top",
      targetHandle: "target-bottom",
    };
  }

  return {
    routeKind: "vertical",
    branchSide: null,
    sourceHandle: "source-top",
    targetHandle: "target-bottom",
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

  const inEdgesMap = new Map<string, Edge[]>();
  const outEdgesMap = new Map<string, Edge[]>();
  nodes.forEach((node) => {
    inEdgesMap.set(node.id, []);
    outEdgesMap.set(node.id, []);
  });

  edges.forEach((edge) => {
    if (inEdgesMap.has(edge.target)) {
      inEdgesMap.get(edge.target)!.push(edge);
    }
    if (outEdgesMap.has(edge.source)) {
      outEdgesMap.get(edge.source)!.push(edge);
    }
  });

  const { ranks } = computeRanks(nodes, inEdgesMap, outEdgesMap);
  const primaryParent = resolvePrimaryParents(nodes, outEdgesMap);
  const primaryChildren = buildPrimaryChildren(edges, primaryParent);
  const lanes = assignLanes(nodes, outEdgesMap, primaryChildren, ranks);

  const layoutNodes = nodes.map((node) => ({
    ...node,
    position: {
      x: (lanes.get(node.id) ?? 0) * (NODE_WIDTH + NODE_SEP_X),
      y: (ranks.get(node.id) ?? 0) * (NODE_HEIGHT + RANK_SEP_Y),
    },
  }));

  const nodePositions = new Map(
    layoutNodes.map((node) => [node.id, node.position]),
  );
  const layoutEdges = edges.map((edge) => {
    const handles = resolveEdgeHandles(edge, nodePositions);
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
