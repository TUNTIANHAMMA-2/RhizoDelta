import { create } from "zustand";

/** Agent 状态（v0.3 预留） */
export interface AgentState {
  active: boolean;
}

export const useAgentStore = create<AgentState>(() => ({
  active: false,
}));
