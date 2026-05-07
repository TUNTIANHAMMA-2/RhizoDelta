import test from "node:test";
import assert from "node:assert/strict";

import {
  ORCHESTRATION_STATUS_STORAGE_KEY,
  mergeOrchestrationStatuses,
  readPersistedOrchestrationStatuses,
  writePersistedOrchestrationStatuses,
} from "./sseStore.ts";

function createMemoryStorage() {
  const values = new Map();
  return {
    getItem(key: string) {
      return values.has(key) ? values.get(key) : null;
    },
    setItem(key: string, value: string) {
      values.set(key, value);
    },
    removeItem(key: string) {
      values.delete(key);
    },
  };
}

test("mergeOrchestrationStatuses should re-key request status to post node id", () => {
  const previous = {
    "req-1": {
      request_id: "req-1",
      event_id: "evt-1",
      post_node_id: null,
      status: "POST_ACCEPTED",
      message: "queued",
    },
  };

  const merged = mergeOrchestrationStatuses(previous, {
    request_id: "req-1",
    event_id: "evt-1",
    post_node_id: "node-1",
    status: "FAILED",
    message: "routing failed",
  });

  assert.equal(merged["req-1"], undefined);
  assert.equal(merged["node-1"].status, "FAILED");
});

test("persisted orchestration statuses should survive a refresh-like reload", () => {
  const storage = createMemoryStorage();
  const statuses = {
    "node-1": {
      request_id: "req-1",
      event_id: "evt-1",
      post_node_id: "node-1",
      status: "FAILED",
      message: "routing failed",
    },
  };

  writePersistedOrchestrationStatuses(statuses, storage);
  const restored = readPersistedOrchestrationStatuses(storage);

  assert.equal(storage.getItem(ORCHESTRATION_STATUS_STORAGE_KEY) !== null, true);
  assert.deepEqual(restored, statuses);
});
