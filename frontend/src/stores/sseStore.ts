import { create } from "zustand";
import type { OrchestrationStatusEvent } from "../api/types";

export const ORCHESTRATION_STATUS_STORAGE_KEY =
  "rhizodelta-orchestration-statuses";

export interface SseState {
  status: "connecting" | "connected" | "disconnected";
  orchestrationStatuses: Record<string, OrchestrationStatusEvent>;
  setStatus: (status: SseState["status"]) => void;
  setOrchestrationStatus: (status: OrchestrationStatusEvent) => void;
}

type StorageLike = Pick<Storage, "getItem" | "setItem" | "removeItem">;

function resolveStorage(storage?: StorageLike): StorageLike | null {
  if (storage) {
    return storage;
  }
  if (typeof globalThis === "undefined" || !("localStorage" in globalThis)) {
    return null;
  }
  return globalThis.localStorage;
}

export function readPersistedOrchestrationStatuses(
  storage?: StorageLike,
): Record<string, OrchestrationStatusEvent> {
  const resolvedStorage = resolveStorage(storage);
  if (!resolvedStorage) {
    return {};
  }
  const raw = resolvedStorage.getItem(ORCHESTRATION_STATUS_STORAGE_KEY);
  if (!raw) {
    return {};
  }
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch {
    resolvedStorage.removeItem(ORCHESTRATION_STATUS_STORAGE_KEY);
    return {};
  }
}

export function writePersistedOrchestrationStatuses(
  statuses: Record<string, OrchestrationStatusEvent>,
  storage?: StorageLike,
): void {
  const resolvedStorage = resolveStorage(storage);
  if (!resolvedStorage) {
    return;
  }
  resolvedStorage.setItem(
    ORCHESTRATION_STATUS_STORAGE_KEY,
    JSON.stringify(statuses),
  );
}

export function mergeOrchestrationStatuses(
  previous: Record<string, OrchestrationStatusEvent>,
  statusEvent: OrchestrationStatusEvent,
): Record<string, OrchestrationStatusEvent> {
  const key = statusEvent.post_node_id || statusEvent.request_id;
  const next = { ...previous, [key]: statusEvent };
  if (
    statusEvent.post_node_id &&
    previous[statusEvent.request_id] &&
    !previous[statusEvent.post_node_id]
  ) {
    delete next[statusEvent.request_id];
  }
  return next;
}

export const useSseStore = create<SseState>((set) => ({
  status: "disconnected",
  orchestrationStatuses: readPersistedOrchestrationStatuses(),
  setStatus: (status) => set({ status }),
  setOrchestrationStatus: (statusEvent) =>
    set((state) => {
      const next = mergeOrchestrationStatuses(
        state.orchestrationStatuses,
        statusEvent,
      );
      writePersistedOrchestrationStatuses(next);
      return { orchestrationStatuses: next };
    }),
}));
