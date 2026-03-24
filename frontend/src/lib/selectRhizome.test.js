import test from "node:test";
import assert from "node:assert/strict";

import { selectRhizome } from "./selectRhizome.ts";

test("selectRhizome should clear selection, close panel, and load the full root graph", async () => {
  const calls = [];

  await selectRhizome("root-42", {
    selectNode: (nodeId) => {
      calls.push(`select:${nodeId}`);
    },
    closeRightPanel: () => {
      calls.push("close-panel");
    },
    loadGraphForRoot: async (nodeId) => {
      calls.push(`load-root:${nodeId}`);
    },
  });

  assert.deepEqual(calls, [
    "select:null",
    "close-panel",
    "load-root:root-42",
  ]);
});
