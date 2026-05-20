import { describe, expect, it } from "vitest";
import {
  markDwellEventSent,
  shouldSendDwellEvent,
} from "./events";

describe("preference event throttling", () => {
  it("allows first dwell event for a node", () => {
    const sentAtByNode = new Map<string, number>();

    expect(shouldSendDwellEvent("node-1", 1_000, sentAtByNode)).toBe(true);
  });

  it("throttles dwell events for the same node for five minutes", () => {
    const sentAtByNode = new Map<string, number>();
    markDwellEventSent("node-1", 1_000, sentAtByNode);

    expect(shouldSendDwellEvent("node-1", 1_000 + 299_999, sentAtByNode)).toBe(false);
    expect(shouldSendDwellEvent("node-1", 1_000 + 300_000, sentAtByNode)).toBe(true);
    expect(shouldSendDwellEvent("node-2", 1_000 + 60_000, sentAtByNode)).toBe(true);
  });
});
