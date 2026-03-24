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

const LINEAGE_CHARGE_STRENGTH = -180;
const LINEAGE_COLLISION_RADIUS = 90;
const LINEAGE_LINK_DISTANCE = 180;
const LINEAGE_TARGET_FORCE_STRENGTH = 0.72;
const LINEAGE_TARGET_FORCE_Y_STRENGTH = 0.76;
const LINEAGE_ALPHA_DECAY = 0.12;

interface LineageTarget {
  x: number;
  y: number;
  fixed: boolean;
}

interface LineageSimulationNode extends SimulationNodeDatum {
  id: string;
  targetX: number;
  targetY: number;
}

interface LineageSimulationLink extends SimulationLinkDatum<LineageSimulationNode> {
  source: string | LineageSimulationNode;
  target: string | LineageSimulationNode;
}

export function buildLineageTargets(
  nodes: Array<Pick<Node, "id" | "position">>,
): Record<string, LineageTarget> {
  return Object.fromEntries(
    nodes.map((node) => [
      node.id,
      {
        x: node.position.x,
        y: node.position.y,
        fixed: false,
      },
    ]),
  ) as Record<string, LineageTarget>;
}

export function createLineageSimulation(
  nodes: Node[],
  edges: Edge[],
): Simulation<LineageSimulationNode, undefined> {
  const targets = buildLineageTargets(nodes);
  const simulationNodes: LineageSimulationNode[] = nodes.map((node) => {
    const target = targets[node.id];
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
  const simulationLinks: LineageSimulationLink[] = edges
    .filter(
      (edge) =>
        simulationNodes.some((node) => node.id === edge.source) &&
        simulationNodes.some((node) => node.id === edge.target),
    )
    .map((edge) => ({
      source: edge.source,
      target: edge.target,
    }));

  return forceSimulation<LineageSimulationNode>(simulationNodes)
    .force("charge", forceManyBody().strength(LINEAGE_CHARGE_STRENGTH))
    .force(
      "link",
      forceLink<LineageSimulationNode, LineageSimulationLink>(simulationLinks)
        .id((node) => node.id)
        .distance(LINEAGE_LINK_DISTANCE),
    )
    .force("collide", forceCollide(LINEAGE_COLLISION_RADIUS))
    .force(
      "targetX",
      forceX<LineageSimulationNode>((node) => node.targetX).strength(
        LINEAGE_TARGET_FORCE_STRENGTH,
      ),
    )
    .force(
      "targetY",
      forceY<LineageSimulationNode>((node) => node.targetY).strength(
        LINEAGE_TARGET_FORCE_Y_STRENGTH,
      ),
    )
    .alphaDecay(LINEAGE_ALPHA_DECAY);
}
