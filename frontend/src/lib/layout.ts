import type { Node, Edge } from "@xyflow/react";

const NODE_WIDTH = 320;
const NODE_HEIGHT = 160;
const NODE_SEP_X = 80;
const RANK_SEP_Y = 120;

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

export function applyTrackLayout(
  nodes: Node[],
  edges: Edge[],
): { nodes: Node[]; edges: Edge[] } {
  if (nodes.length === 0) return { nodes, edges };

  const inEdgesMap = new Map<string, Edge[]>();
  const outEdgesMap = new Map<string, Edge[]>();

  nodes.forEach((n) => {
    inEdgesMap.set(n.id, []);
    outEdgesMap.set(n.id, []);
  });

  edges.forEach((e) => {
    if (inEdgesMap.has(e.target)) inEdgesMap.get(e.target)!.push(e);
    if (outEdgesMap.has(e.source)) outEdgesMap.get(e.source)!.push(e);
  });

  // 1. Compute ranks (Y-axis depth) using Topological Sort
  // NOTE: ReactFlow edges in this project go from child(source) -> parent(target).
  // Therefore, roots (oldest nodes) have outDegree = 0.
  const ranks = new Map<string, number>();
  const outDegree = new Map<string, number>();
  nodes.forEach((n) => outDegree.set(n.id, outEdgesMap.get(n.id)!.length));

  const queue = nodes.filter((n) => outDegree.get(n.id) === 0).map((n) => n.id);
  nodes.forEach((n) => ranks.set(n.id, 0));

  const topoOrder: string[] = [];
  while (queue.length > 0) {
    const curr = queue.shift()!;
    topoOrder.push(curr);
    const currRank = ranks.get(curr)!;

    for (const edge of inEdgesMap.get(curr)!) {
      const child = edge.source; // Edge is child -> parent(curr)
      ranks.set(child, Math.max(ranks.get(child)!, currRank + 1));
      outDegree.set(child, outDegree.get(child)! - 1);
      if (outDegree.get(child) === 0) {
        queue.push(child);
      }
    }
  }

  // Fallback for cycles (shouldn't happen in DAG, but just in case)
  nodes.forEach((n) => {
    if (!topoOrder.includes(n.id)) {
      topoOrder.push(n.id);
      if (!ranks.has(n.id)) ranks.set(n.id, 0);
    }
  });

  // 2. Identify Primary Parent for each node to establish structural spines
  // A node (child) may point to multiple parents. Pick the highest priority parent.
  const primaryParent = new Map<string, string>();
  nodes.forEach((n) => {
    const outEdges = outEdgesMap.get(n.id)!;
    if (outEdges.length > 0) {
      outEdges.sort((a, b) => {
        const pA = edgePriority(a.data?.relType as string);
        const pB = edgePriority(b.data?.relType as string);
        if (pA === pB) {
          const dateA = a.data?.createdAt ? new Date(a.data.createdAt as string).getTime() : 0;
          const dateB = b.data?.createdAt ? new Date(b.data.createdAt as string).getTime() : 0;
          if (dateA !== dateB) return dateA - dateB;
          return a.target.localeCompare(b.target);
        }
        return pA - pB;
      });
      primaryParent.set(n.id, outEdges[0].target);
    }
  });

  // 3. Define the main Spine for each parent (the child that strictly follows it)
  const spineNext = new Map<string, string>();
  nodes.forEach((n) => {
    const inEdges = inEdgesMap.get(n.id)!;
    const spineCandidates = inEdges.filter((e) => primaryParent.get(e.source) === n.id);
    if (spineCandidates.length > 0) {
      spineCandidates.sort((a, b) => {
        const pA = edgePriority(a.data?.relType as string);
        const pB = edgePriority(b.data?.relType as string);
        if (pA === pB) {
          const dateA = a.data?.createdAt ? new Date(a.data.createdAt as string).getTime() : 0;
          const dateB = b.data?.createdAt ? new Date(b.data.createdAt as string).getTime() : 0;
          if (dateA !== dateB) return dateA - dateB;
          return a.source.localeCompare(b.source);
        }
        return pA - pB;
      });
      spineNext.set(n.id, spineCandidates[0].source);
    }
  });

  const isSpineHead = new Set<string>(nodes.map((n) => n.id));
  for (const childId of spineNext.values()) {
    isSpineHead.delete(childId);
  }

  // 4. Discover spines via DFS to maintain locality (branches near their parents)
  const visitedSpines = new Set<string>();
  const orderedSpineHeads: string[] = [];

  function discoverSpines(nodeId: string) {
    let head = nodeId;
    while (!isSpineHead.has(head)) {
      const p = primaryParent.get(head);
      if (!p || spineNext.get(p) !== head) break;
      head = p;
    }

    if (visitedSpines.has(head)) return;
    visitedSpines.add(head);
    orderedSpineHeads.push(head);

    let curr: string | undefined = head;
    while (curr) {
      const inEdges = inEdgesMap.get(curr)!;
      inEdges.sort((a, b) => {
        const pA = edgePriority(a.data?.relType as string);
        const pB = edgePriority(b.data?.relType as string);
        if (pA === pB) {
          const dateA = a.data?.createdAt ? new Date(a.data.createdAt as string).getTime() : 0;
          const dateB = b.data?.createdAt ? new Date(b.data.createdAt as string).getTime() : 0;
          if (dateA !== dateB) return dateA - dateB;
          return a.source.localeCompare(b.source);
        }
        return pA - pB;
      });

      for (const edge of inEdges) {
        discoverSpines(edge.source);
      }
      curr = spineNext.get(curr);
    }
  }

  topoOrder.forEach((nodeId) => discoverSpines(nodeId));

  // Build the spine arrays
  const spines: string[][] = [];
  for (const head of orderedSpineHeads) {
    const spine: string[] = [];
    let curr: string | undefined = head;
    while (curr) {
      spine.push(curr);
      curr = spineNext.get(curr);
    }
    spines.push(spine);
  }

  // 5. Assign tracks (X-axis) with collision prevention
  const trackOccupancy = new Map<number, Set<number>>();
  const nodeTracks = new Map<string, number>();

  for (const spine of spines) {
    let minRank = Infinity;
    let maxRank = -Infinity;
    for (const nodeId of spine) {
      const r = ranks.get(nodeId)!;
      if (r < minRank) minRank = r;
      if (r > maxRank) maxRank = r;
    }

    let trackIndex = 0;
    while (true) {
      let isFree = true;
      // Reserve the entire span of ranks for this spine to prevent edges crossing over other nodes
      for (let r = minRank; r <= maxRank; r++) {
        if (trackOccupancy.get(trackIndex)?.has(r)) {
          isFree = false;
          break;
        }
      }

      if (isFree) {
        if (!trackOccupancy.has(trackIndex)) {
          trackOccupancy.set(trackIndex, new Set());
        }
        for (let r = minRank; r <= maxRank; r++) {
          trackOccupancy.get(trackIndex)!.add(r);
        }
        for (const nodeId of spine) {
          nodeTracks.set(nodeId, trackIndex);
        }
        break;
      }
      trackIndex++;
    }
  }

  // 6. Generate final coordinates
  const layoutNodes = nodes.map((node) => {
    const track = nodeTracks.get(node.id) || 0;
    const rank = ranks.get(node.id) || 0;

    return {
      ...node,
      position: {
        x: track * (NODE_WIDTH + NODE_SEP_X),
        y: rank * (NODE_HEIGHT + RANK_SEP_Y),
      },
    };
  });

  return { nodes: layoutNodes, edges };
}
