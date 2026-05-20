import { request } from "./client";

export type PreferenceEventType = "VIEW" | "EXPAND" | "DWELL" | "LIKE" | "SHARE";

export interface PreferenceEventRequest {
  type: PreferenceEventType;
  topicId?: string | null;
  sourceNodeId: string;
}

const DWELL_THROTTLE_MS = 5 * 60 * 1000;

const dwellSentAtByNode = new Map<string, number>();

export function sendPreferenceEvent(event: PreferenceEventRequest) {
  return request<void>("/api/users/me/events", {
    method: "POST",
    body: JSON.stringify(event),
  });
}

export function sendPreferenceEventBestEffort(event: PreferenceEventRequest) {
  void sendPreferenceEvent(event).catch(() => {
    // 交互埋点失败不应打断图谱操作。
  });
}

export function shouldSendDwellEvent(
  nodeId: string,
  now = Date.now(),
  sentAtByNode = dwellSentAtByNode,
) {
  const lastSentAt = sentAtByNode.get(nodeId);
  return lastSentAt == null || now - lastSentAt >= DWELL_THROTTLE_MS;
}

export function markDwellEventSent(
  nodeId: string,
  now = Date.now(),
  sentAtByNode = dwellSentAtByNode,
) {
  sentAtByNode.set(nodeId, now);
}

export function sendDwellEventOncePerWindow(nodeId: string, now = Date.now()) {
  if (!shouldSendDwellEvent(nodeId, now)) {
    return false;
  }
  markDwellEventSent(nodeId, now);
  sendPreferenceEventBestEffort({
    type: "DWELL",
    sourceNodeId: nodeId,
  });
  return true;
}
