import type { Node, Edge } from "@xyflow/react";
import type { GraphNodeDTO, GraphEdgeDTO, NodeLabel } from "../api/types";

const NODE_TYPE_MAP: Record<NodeLabel, string> = {
  Human_Post: "humanPost",
  AI_Consensus: "consensus",
  Result: "result",
};

export function toRfNode(dto: GraphNodeDTO): Node {
  return {
    id: dto.node_id,
    type: NODE_TYPE_MAP[dto.label] ?? "humanPost",
    position: { x: 0, y: 0 },
    origin: [0.5, 0.5],
    data: dto as unknown as Record<string, unknown>,
  };
}

export function toRfEdge(dto: GraphEdgeDTO): Edge {
  const relType = dto.type;
  const isBranch = relType === "BRANCHED_FROM";

  return {
    id: `${dto.source}-${dto.type}-${dto.target}`,
    source: dto.source,
    target: dto.target,
    type: "versionEdge",
    sourceHandle: isBranch ? "source-left" : "source-top",
    targetHandle: isBranch ? "target-right" : "target-bottom",
    data: {
      relType,
      createdAt: dto.created_at,
      routeKind: isBranch ? "branch" : relType === "CONTINUES_FROM" ? "continue" : "vertical",
      branchSide: null,
    },
    style: { stroke: "var(--color-edge-default)", strokeWidth: 1.5 },
  };
}
