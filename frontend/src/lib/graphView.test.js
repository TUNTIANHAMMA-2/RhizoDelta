import test from "node:test";
import assert from "node:assert/strict";

import { buildGraphViews } from "./graphView.ts";

const SAMPLE_NODES = [
  {
    node_id: "root",
    label: "Human_Post",
    content: "root",
    summary_content: null,
    author_id: "u1",
    agent_version: null,
    operation_id: null,
    created_at: "2026-03-24T00:00:00Z",
    has_embedding: false,
  },
  {
    node_id: "continue-1",
    label: "Human_Post",
    content: "continue",
    summary_content: null,
    author_id: "u1",
    agent_version: null,
    operation_id: null,
    created_at: "2026-03-24T00:01:00Z",
    has_embedding: false,
  },
  {
    node_id: "branch-1",
    label: "Human_Post",
    content: "branch",
    summary_content: null,
    author_id: "u2",
    agent_version: null,
    operation_id: null,
    created_at: "2026-03-24T00:02:00Z",
    has_embedding: false,
  },
];

const SAMPLE_EDGES = [
  {
    source: "continue-1",
    target: "root",
    type: "CONTINUES_FROM",
    created_at: "2026-03-24T00:01:00Z",
  },
  {
    source: "branch-1",
    target: "root",
    type: "BRANCHED_FROM",
    created_at: "2026-03-24T00:02:00Z",
  },
];

test("buildGraphViews should bootstrap explore positions from lineage layout", () => {
  const views = buildGraphViews(SAMPLE_NODES, SAMPLE_EDGES);
  const lineageRoot = views.lineage.nodes.find((node) => node.id === "root");
  const exploreRoot = views.explore.nodes.find((node) => node.id === "root");

  assert.ok(lineageRoot);
  assert.ok(exploreRoot);
  assert.deepEqual(exploreRoot.position, lineageRoot.position);
  assert.equal(views.explore.edges[0].data.viewMode, "explore");
});

test("buildGraphViews should preserve prior explore positions during refresh", () => {
  const priorPositions = new Map([
    ["branch-1", { x: 960, y: 240 }],
  ]);

  const views = buildGraphViews(SAMPLE_NODES, SAMPLE_EDGES, priorPositions);
  const exploreBranch = views.explore.nodes.find((node) => node.id === "branch-1");
  const lineageBranch = views.lineage.nodes.find((node) => node.id === "branch-1");

  assert.ok(exploreBranch);
  assert.ok(lineageBranch);
  assert.deepEqual(exploreBranch.position, { x: 960, y: 240 });
  assert.notDeepEqual(lineageBranch.position, exploreBranch.position);
});
