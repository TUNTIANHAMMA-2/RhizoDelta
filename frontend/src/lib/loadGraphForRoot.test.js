import test from "node:test";
import assert from "node:assert/strict";

import { loadGraphForRoot } from "./loadGraphForRoot.ts";

test("loadGraphForRoot delegates to loadTopologyContext exactly once", async () => {
  const calls = [];

  await loadGraphForRoot("root-1", {
    loadTopologyContext: async (nodeId) => {
      calls.push(`topology-context:${nodeId}`);
    },
  });

  assert.deepEqual(calls, ["topology-context:root-1"]);
});

test("loadGraphForRoot propagates loadTopologyContext failures", async () => {
  await assert.rejects(
    loadGraphForRoot("root-2", {
      loadTopologyContext: async () => {
        throw new Error("aggregate call failed");
      },
    }),
    /aggregate call failed/,
  );
});
