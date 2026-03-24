import { create } from "zustand";
import type { OrchestrationStatusEvent } from "../api/types";

export interface SseState {
  status: "connecting" | "connected" | "disconnected";
  orchestrationStatuses: Record<string, OrchestrationStatusEvent>;
  setStatus: (status: SseState["status"]) => void;
  setOrchestrationStatus: (status: OrchestrationStatusEvent) => void;
}

export const useSseStore = create<SseState>((set) => ({
  status: "disconnected",
  orchestrationStatuses: {},
  setStatus: (status) => set({ status }),
  setOrchestrationStatus: (statusEvent) =>
    set((state) => {
      const key = statusEvent.post_node_id || statusEvent.request_id;
      const prev = state.orchestrationStatuses;
      // When post_node_id arrives, migrate any entry previously keyed by request_id
      const next = { ...prev, [key]: statusEvent };
      if (statusEvent.post_node_id && prev[statusEvent.request_id] && !prev[statusEvent.post_node_id]) {
        delete next[statusEvent.request_id];
      }
      return { orchestrationStatuses: next };
    }),
}));
