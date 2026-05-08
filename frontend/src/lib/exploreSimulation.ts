import {
  forceCollide,
  forceLink,
  forceManyBody,
  forceSimulation,
  forceX,
  forceY,
  forceCenter,
  type Simulation,
  type SimulationLinkDatum,
  type SimulationNodeDatum,
} from "d3-force";
import type { Edge, Node } from "@xyflow/react";

const CHARGE_STRENGTH = -4000;
const COLLIDE_RADIUS = 220;
const COLLIDE_ITERATIONS = 6;
const GRAVITY_STRENGTH = 0.012;
const ALPHA_DECAY = 0.04;

interface ExploreSimulationNode extends SimulationNodeDatum {
  id: string;
}

interface ExploreSimulationLink extends SimulationLinkDatum<ExploreSimulationNode> {
  source: string | ExploreSimulationNode;
  target: string | ExploreSimulationNode;
  edgeData: Edge;
}

/**
 * BFS-based radial tree to assign initial node positions.
 * This guarantees nodes spawn in non-overlapping, outward-expanding 
 * concentric rings with correctly partitioned angle sweeps, drastically 
 * avoiding initial tangles and crossed edges before D3 resolves physics.
 */
function assignRadialTreeInitialPositions(
  nodes: Node[],
  edges: Edge[],
  anchorNodeId: string,
) {
  const adj = new Map<string, string[]>();
  nodes.forEach((n) => adj.set(n.id, []));
  edges.forEach((e) => {
    if (adj.has(e.source) && adj.has(e.target)) {
      adj.get(e.source)!.push(e.target);
      adj.get(e.target)!.push(e.source);
    }
  });

  const positions = new Map<string, { x: number; y: number }>();
  positions.set(anchorNodeId, { x: 0, y: 0 });

  const BASE_RADIUS = 400;
  const queue: Array<{ id: string; startAngle: number; sweep: number; level: number }> = [];
  const visited = new Set<string>([anchorNodeId]);

  queue.push({
    id: anchorNodeId,
    startAngle: 0,
    sweep: 2 * Math.PI,
    level: 0,
  });

  while (queue.length > 0) {
    const { id, startAngle, sweep, level } = queue.shift()!;
    const neighbors = (adj.get(id) || []).filter((v) => !visited.has(v));
    const count = neighbors.length;

    if (count > 0) {
      const childSweep = sweep / count;
      neighbors.forEach((childId, i) => {
        visited.add(childId);
        const childStartAngle = startAngle + i * childSweep;
        const middleAngle = childStartAngle + childSweep / 2;
        const R = BASE_RADIUS * (level + 1);

        positions.set(childId, {
          x: R * Math.cos(middleAngle),
          y: R * Math.sin(middleAngle),
        });

        queue.push({
          id: childId,
          startAngle: childStartAngle,
          sweep: childSweep,
          level: level + 1,
        });
      });
    }
  }

  // Handle theoretically possible disconnected island nodes
  let disconnectedCount = 0;
  for (const node of nodes) {
    if (!positions.has(node.id)) {
      const R = BASE_RADIUS * 2;
      const angle = disconnectedCount * 0.8;
      positions.set(node.id, {
        x: R * Math.cos(angle),
        y: R * Math.sin(angle),
      });
      disconnectedCount++;
    }
  }

  return positions;
}

export function createExploreSimulation(
  nodes: Node[],
  edges: Edge[],
  anchorNodeId: string,
): Simulation<ExploreSimulationNode, undefined> {
  const initialPositions = assignRadialTreeInitialPositions(nodes, edges, anchorNodeId);

  // Map react-flow nodes to D3-compatible nodes
  const simulationNodes: ExploreSimulationNode[] = nodes.map((node) => {
    const isNewGenerativeSpawn = Math.abs(node.position.x) <= 5 && Math.abs(node.position.y) <= 5;
    const defaultPos = initialPositions.get(node.id) || { x: node.position.x, y: node.position.y };
    // If the node hasn't been placed (is at origin), or we want to cleanly restart from anchor, 
    // we use the smart radial position to avoid overlaps.
    const startX = isNewGenerativeSpawn ? defaultPos.x : node.position.x;
    const startY = isNewGenerativeSpawn ? defaultPos.y : node.position.y;

    return {
      id: node.id,
      x: startX,
      y: startY,
      // Gently fix the anchor node to completely stabilize the view origin, 
      // the rest will organically float around it
      fx: node.id === anchorNodeId ? 0 : null,
      fy: node.id === anchorNodeId ? 0 : null,
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
      edgeData: edge,
    }));

  return forceSimulation<ExploreSimulationNode>(simulationNodes)
    // 1. Non-linear Growth & Repulsion (Rhizomatic spread)
    .force(
      "charge",
      forceManyBody().strength(CHARGE_STRENGTH),
    )
    // 2. Strict overlap prevention
    .force(
      "collide",
      forceCollide(COLLIDE_RADIUS).iterations(COLLIDE_ITERATIONS),
    )
    // 3. Elastic Link Distance based on Semantic Edge Type
    .force(
      "link",
      forceLink<ExploreSimulationNode, ExploreSimulationLink>(simulationLinks)
        .id((node) => node.id)
        .distance((link) => {
          const relType = String(link.edgeData.data?.relType ?? "");
          if (relType === "CONTINUES_FROM") {
            return 250;
          }
          if (relType === "BRANCHED_FROM") {
            return 400;
          }
          return 320;
        }),
    )
    // 4. Breathe & Float
    .force("forceX", forceX(0).strength(GRAVITY_STRENGTH))
    .force("forceY", forceY(0).strength(GRAVITY_STRENGTH))
    .force("center", forceCenter(0, 0))
    .alphaDecay(ALPHA_DECAY);
}
