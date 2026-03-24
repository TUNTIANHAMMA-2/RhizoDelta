import test from "node:test";
import assert from "node:assert/strict";

import { applyTrackLayout } from "./layout.ts";

test("applyTrackLayout should place the first branch beside its source root", () => {
  const nodes = [
    { id: "root", position: { x: 0, y: 0 }, data: {} },
    { id: "branch-1", position: { x: 0, y: 0 }, data: {} },
  ];
  const edges = [
    {
      id: "branch-1-BRANCHED_FROM-root",
      source: "branch-1",
      target: "root",
      data: {
        relType: "BRANCHED_FROM",
        createdAt: "2026-03-24T00:00:00.000Z",
      },
    },
  ];

  const { nodes: layoutNodes } = applyTrackLayout(nodes, edges);
  const rootNode = layoutNodes.find((node) => node.id === "root");
  const branchNode = layoutNodes.find((node) => node.id === "branch-1");

  assert.ok(rootNode);
  assert.ok(branchNode);
  assert.equal(branchNode.position.y, rootNode.position.y);
  assert.notEqual(branchNode.position.x, rootNode.position.x);
});

test("applyTrackLayout should spread root branches across both sides", () => {
  const nodes = [
    { id: "root", position: { x: 0, y: 0 }, data: {} },
    { id: "branch-1", position: { x: 0, y: 0 }, data: {} },
    { id: "branch-2", position: { x: 0, y: 0 }, data: {} },
    { id: "branch-3", position: { x: 0, y: 0 }, data: {} },
  ];
  const edges = [
    {
      id: "branch-1-BRANCHED_FROM-root",
      source: "branch-1",
      target: "root",
      data: { relType: "BRANCHED_FROM", createdAt: "2026-03-24T00:00:00.000Z" },
    },
    {
      id: "branch-2-BRANCHED_FROM-root",
      source: "branch-2",
      target: "root",
      data: { relType: "BRANCHED_FROM", createdAt: "2026-03-24T00:00:01.000Z" },
    },
    {
      id: "branch-3-BRANCHED_FROM-root",
      source: "branch-3",
      target: "root",
      data: { relType: "BRANCHED_FROM", createdAt: "2026-03-24T00:00:02.000Z" },
    },
  ];

  const { nodes: layoutNodes } = applyTrackLayout(nodes, edges);
  const rootNode = layoutNodes.find((node) => node.id === "root");
  const branchNodes = layoutNodes.filter((node) => node.id !== "root");

  assert.ok(rootNode);
  assert.equal(branchNodes.length, 3);
  assert.ok(branchNodes.every((node) => node.position.y === rootNode.position.y));
  assert.ok(branchNodes.some((node) => node.position.x < rootNode.position.x));
  assert.ok(branchNodes.some((node) => node.position.x > rootNode.position.x));
});

test("applyTrackLayout should keep a continuation on the branch lane", () => {
  const nodes = [
    { id: "root", position: { x: 0, y: 0 }, data: {} },
    { id: "branch-1", position: { x: 0, y: 0 }, data: {} },
    { id: "continue-1", position: { x: 0, y: 0 }, data: {} },
  ];
  const edges = [
    {
      id: "branch-1-BRANCHED_FROM-root",
      source: "branch-1",
      target: "root",
      data: { relType: "BRANCHED_FROM", createdAt: "2026-03-24T00:00:00.000Z" },
    },
    {
      id: "continue-1-CONTINUES_FROM-branch-1",
      source: "continue-1",
      target: "branch-1",
      data: { relType: "CONTINUES_FROM", createdAt: "2026-03-24T00:00:01.000Z" },
    },
  ];

  const { nodes: layoutNodes } = applyTrackLayout(nodes, edges);
  const rootNode = layoutNodes.find((node) => node.id === "root");
  const branchNode = layoutNodes.find((node) => node.id === "branch-1");
  const continueNode = layoutNodes.find((node) => node.id === "continue-1");

  assert.ok(rootNode);
  assert.ok(branchNode);
  assert.ok(continueNode);
  assert.notEqual(branchNode.position.x, rootNode.position.x);
  assert.equal(continueNode.position.x, branchNode.position.x);
  assert.ok(continueNode.position.y > branchNode.position.y);
});
