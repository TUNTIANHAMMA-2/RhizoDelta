import test from "node:test";
import assert from "node:assert/strict";

import { loadGraphForRoot } from "./loadGraphForRoot.ts";

test("loadGraphForRoot should load lineage before children", async () => {
  const calls = [];

  await loadGraphForRoot("root-1", {
    loadLineage: async (nodeId) => {
      calls.push(`lineage:${nodeId}`);
    },
    loadChildren: async (nodeId) => {
      calls.push(`children:${nodeId}`);
    },
  });

  assert.deepEqual(calls, ["lineage:root-1", "children:root-1"]);
});

test("loadGraphForRoot should keep lineage when children loading fails", async () => {
  const calls = [];
  let capturedError = null;

  await loadGraphForRoot("root-2", {
    loadLineage: async (nodeId) => {
      calls.push(`lineage:${nodeId}`);
    },
    loadChildren: async () => {
      throw new Error("children failed");
    },
    onChildrenError: (error) => {
      capturedError = error;
      calls.push("children-error");
    },
  });

  assert.deepEqual(calls, ["lineage:root-2", "children-error"]);
  assert.equal(capturedError?.message, "children failed");
});
