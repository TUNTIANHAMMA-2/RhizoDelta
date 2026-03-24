import test from "node:test";
import assert from "node:assert/strict";

import { buildLineageTargets } from "./lineageSimulation.ts";

test("buildLineageTargets should keep every node anchored to its track position", () => {
  const targets = buildLineageTargets([
    { id: "root", position: { x: 0, y: 0 } },
    { id: "branch-1", position: { x: 400, y: 0 } },
    { id: "continue-1", position: { x: 0, y: 280 } },
  ]);

  assert.deepEqual(targets.root, { x: 0, y: 0, fixed: false });
  assert.deepEqual(targets["branch-1"], { x: 400, y: 0, fixed: false });
  assert.deepEqual(targets["continue-1"], { x: 0, y: 280, fixed: false });
});

test("buildLineageTargets should return an empty object for an empty graph", () => {
  assert.deepEqual(buildLineageTargets([]), {});
});
