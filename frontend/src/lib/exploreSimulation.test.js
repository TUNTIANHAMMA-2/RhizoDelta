import test from "node:test";
import assert from "node:assert/strict";

import { buildExploreTargets } from "./exploreSimulation.ts";

const nodes = [
  { id: "root", position: { x: 0, y: 0 } },
  { id: "continue-1", position: { x: 0, y: 0 } },
  { id: "branch-left", position: { x: 0, y: 0 } },
  { id: "branch-right", position: { x: 0, y: 0 } },
  { id: "merge-1", position: { x: 0, y: 0 } },
];

const edges = [
  {
    id: "e1",
    source: "continue-1",
    target: "root",
    data: { relType: "CONTINUES_FROM" },
  },
  {
    id: "e2",
    source: "branch-left",
    target: "root",
    data: { relType: "BRANCHED_FROM", createdAt: "2026-03-24T00:01:00Z" },
  },
  {
    id: "e3",
    source: "branch-right",
    target: "root",
    data: { relType: "BRANCHED_FROM", createdAt: "2026-03-24T00:02:00Z" },
  },
  {
    id: "e4",
    source: "merge-1",
    target: "root",
    data: { relType: "CONVERGED_FROM" },
  },
];

test("buildExploreTargets should pin the anchor and separate branch lanes", () => {
  const targets = buildExploreTargets(nodes, edges, "root");

  assert.deepEqual(targets.root, { x: 0, y: 0, fixed: true });
  assert.equal(targets["continue-1"].x, 0);
  assert.equal(targets["continue-1"].y > 0, true);
  assert.equal(targets["merge-1"].y < 0, true);
  assert.equal(targets["branch-left"].x > 0, true);
  assert.equal(targets["branch-right"].x < 0, true);
});

test("buildExploreTargets should place parent continuations above the anchor", () => {
  const parentTargets = buildExploreTargets(
    [
      { id: "child", position: { x: 0, y: 0 } },
      { id: "parent", position: { x: 0, y: 0 } },
    ],
    [
      {
        id: "e-parent",
        source: "child",
        target: "parent",
        data: { relType: "CONTINUES_FROM" },
      },
    ],
    "child",
  );

  assert.equal(parentTargets.parent.y < 0, true);
});
