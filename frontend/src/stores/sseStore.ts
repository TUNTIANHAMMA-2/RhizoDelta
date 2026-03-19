import { create } from "zustand";

export interface SseState {
  status: "connecting" | "connected" | "disconnected";
  setStatus: (status: SseState["status"]) => void;
}

export const useSseStore = create<SseState>((set) => ({
  status: "disconnected",
  setStatus: (status) => set({ status }),
}));
