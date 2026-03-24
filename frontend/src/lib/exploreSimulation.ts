import {
  forceCollide,
  forceLink,
  forceManyBody,
  forceSimulation,
  forceX,
  forceY,
  type Simulation,
  type SimulationLinkDatum,
  type SimulationNodeDatum,
} from "d3-force";
import type { Edge, Node } from "@xyflow/react";

const BRANCH_X_STEP = 280;
const BRANCH_Y_OFFSET = 40;
const CONTINUE_Y_STEP = 240;
const MERGE_Y_STEP = -180;
const CHARGE_STRENGTH = -700;
const COLLISION_RADIUS = 110;
const LINK_DISTANCE = 220;
const TARGET_FORCE_STRENGTH = 0.28;
const TARGET_FORCE_Y_STRENGTH = 0.34;
const ALPHA_DECAY = 0.08;

interface ExploreTarget {
  x: number;
  y: number;
  fixed: boolean;
}

interface ExploreSimulationNode extends SimulationNodeDatum {
  id: string;
  targetX: number;
  targetY: number;
}

interface ExploreSimulationLink extends SimulationLinkDatum<ExploreSimulationNode> {
  source: string | ExploreSimulationNode;
  target: string | ExploreSimulationNode;
}

function toTimestamp(edge: Edge) {
  const value = edge.data?.createdAt;
  return value ? new Date(String(value)).getTime() : 0;
}

function stableCompare(left: Edge, right: Edge) {
  const timestampDiff = toTimestamp(left) - toTimestamp(right);
  if (timestampDiff !== 0) {
    return timestampDiff;
  }
  return left.id.localeCompare(right.id);
}

function branchOffset(index: number) {
  const step = Math.floor(index / 2) + 1;
  return index % 2 === 0 ? step : -step;
}

function relationDirection(edge: Edge, anchorNodeId: string) {
  if (edge.source === anchorNodeId) {
    return "outgoing";
  }
  if (edge.target === anchorNodeId) {
    return "incoming";
  }
  return "disconnected";
}

function resolveRelationTarget(
  edge: Edge,
  anchorNodeId: string,
  branchIndexMap: Map<string, number>,
): ExploreTarget | null {
  const relType = String(edge.data?.relType ?? "");
  const direction = relationDirection(edge, anchorNodeId);
  const neighborId = edge.source === anchorNodeId ? edge.target : edge.source;

  if (direction === "disconnected") {
    return null;
  }

  if (relType === "CONTINUES_FROM") {
    const y = direction === "incoming" ? CONTINUE_Y_STEP : -CONTINUE_Y_STEP;
    return { x: 0, y, fixed: false };
  }

  if (relType === "BRANCHED_FROM") {
    const offset = branchOffset(branchIndexMap.get(neighborId) ?? 0);
    return {
      x: offset * BRANCH_X_STEP,
      y: BRANCH_Y_OFFSET * Math.abs(offset),
      fixed: false,
    };
  }

  if (relType === "CONVERGED_FROM" || relType === "MERGED_INTO") {
    return { x: 0, y: MERGE_Y_STEP, fixed: false };
  }

  return {
    x: direction === "incoming" ? BRANCH_X_STEP * 0.6 : -BRANCH_X_STEP * 0.6,
    y: 0,
    fixed: false,
  };
}

export function buildExploreTargets(
  nodes: Array<Pick<Node, "id" | "position">>,
  edges: Edge[],
  anchorNodeId: string,
): Record<string, ExploreTarget> {
  const targets = Object.fromEntries(
    nodes.map((node) => [
      node.id,
      { x: node.position.x, y: node.position.y, fixed: false },
    ]),
  ) as Record<string, ExploreTarget>;
  targets[anchorNodeId] = { x: 0, y: 0, fixed: true };

  const branchEdges = edges
    .filter(
      (edge) =>
        String(edge.data?.relType ?? "") === "BRANCHED_FROM" &&
        (edge.source === anchorNodeId || edge.target === anchorNodeId),
    )
    .sort(stableCompare);
  const branchIndexMap = new Map(
    branchEdges.map((edge, index) => [
      edge.source === anchorNodeId ? edge.target : edge.source,
      index,
    ]),
  );

  for (const edge of edges) {
    const target = resolveRelationTarget(edge, anchorNodeId, branchIndexMap);
    if (!target) {
      continue;
    }
    const nodeId = edge.source === anchorNodeId ? edge.target : edge.source;
    targets[nodeId] = target;
  }

  return targets;
}

export function createExploreSimulation(
  nodes: Node[],
  edges: Edge[],
  anchorNodeId: string,
): Simulation<ExploreSimulationNode, undefined> {
  const targets = buildExploreTargets(nodes, edges, anchorNodeId);
  const simulationNodes: ExploreSimulationNode[] = nodes.map((node) => {
    const target = targets[node.id] ?? {
      x: node.position.x,
      y: node.position.y,
      fixed: false,
    };
    return {
      id: node.id,
      x: node.position.x,
      y: node.position.y,
      fx: target.fixed ? target.x : null,
      fy: target.fixed ? target.y : null,
      targetX: target.x,
      targetY: target.y,
    };
  });
  const simulationLinks: ExploreSimulationLink[] = edges
    .filter(
      (edge) =>
        simulationNodes.some((node) => node.id === edge.source) &&
        simulationNodes.some((node) => node.id === edge.target),
    )
    .map((edge) => ({
      source: edge.source,
      target: edge.target,
    }));

  return forceSimulation<ExploreSimulationNode>(simulationNodes)
    .force("charge", forceManyBody().strength(CHARGE_STRENGTH))
    .force(
      "link",
      forceLink<ExploreSimulationNode, ExploreSimulationLink>(simulationLinks)
        .id((node) => node.id)
        .distance(LINK_DISTANCE),
    )
    .force("collide", forceCollide(COLLISION_RADIUS))
    .force(
      "targetX",
      forceX<ExploreSimulationNode>(
        (node: ExploreSimulationNode) => node.targetX,
      ).strength(
        TARGET_FORCE_STRENGTH,
      ),
    )
    .force(
      "targetY",
      forceY<ExploreSimulationNode>(
        (node: ExploreSimulationNode) => node.targetY,
      ).strength(
        TARGET_FORCE_Y_STRENGTH,
      ),
    )
    .alphaDecay(ALPHA_DECAY);
}
