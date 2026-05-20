import { describe, expect, it } from "vitest";

import {
  ORCHESTRATION_STATUS_STORAGE_KEY,
  mergeOrchestrationStatuses,
  readPersistedOrchestrationStatuses,
  writePersistedOrchestrationStatuses,
} from "./sseStore.ts";

function createMemoryStorage(): Storage {
  const values = new Map<string, string>();
  return {
    get length() {
      return values.size;
    },
    clear() {
      values.clear();
    },
    getItem(key: string) {
      return values.has(key) ? values.get(key) ?? null : null;
    },
    key(index: number) {
      return [...values.keys()][index] ?? null;
    },
    setItem(key: string, value: string) {
      values.set(key, value);
    },
    removeItem(key: string) {
      values.delete(key);
    },
  };
}

describe("sseStore persistence helpers", () => {
  it("mergeOrchestrationStatuses should re-key request status to post node id", () => {
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

  expect(merged["req-1"]).toBeUndefined();
  expect(merged["node-1"].status).toBe("FAILED");
  });

  it("persisted orchestration statuses should survive a refresh-like reload", () => {
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

  expect(storage.getItem(ORCHESTRATION_STATUS_STORAGE_KEY) !== null).toBe(true);
  expect(restored).toEqual(statuses);
  });
});
