import { create } from "zustand";
import type { Viewport } from "@xyflow/react";

export type RightPanelMode = "hidden" | "detail" | "edit" | "agent" | "review";
export type NodeTab = "details" | "provenance" | "association" | "audit";
export type CanvasMode = "lineage" | "explore";

export interface ToastMessage {
  id: string;
  type: "info" | "success" | "warning" | "error";
  message: string;
}

const DEFAULT_VIEWPORT: Viewport = { x: 0, y: 0, zoom: 1 };

export interface UiState {
  leftSidebarOpen: boolean;
  toggleLeftSidebar: () => void;

  rightPanelMode: RightPanelMode;
  rightPanelPayload: {
    nodeId: string;
    formType?: "inject" | "fork" | "post";
  } | null;
  openDetailPanel: (nodeId: string) => void;
  openEditPanel: (nodeId: string, formType: "inject" | "fork") => void;
  openPostPanel: () => void;
  openReviewPanel: () => void;
  closeRightPanel: () => void;

  headerExpanded: boolean;
  setHeaderExpanded: (v: boolean) => void;

  zoomLevel: number;
  setZoomLevel: (zoom: number) => void;
  canvasMode: CanvasMode;
  setCanvasMode: (mode: CanvasMode) => void;

  // Viewport persistence
  viewports: { lineage: Viewport; explore: Viewport };
  saveViewport: (mode: CanvasMode, viewport: Viewport) => void;

  // Toast
  toasts: ToastMessage[];
  addToast: (toast: Omit<ToastMessage, "id">) => void;
  removeToast: (id: string) => void;

  // Node detail tabs
  activeNodeTab: NodeTab;
  setActiveNodeTab: (tab: NodeTab) => void;

  // Mobile
  isMobileMenuOpen: boolean;
  setMobileMenuOpen: (v: boolean) => void;
}

let toastId = 0;

export const useUiStore = create<UiState>((set, get) => ({
  leftSidebarOpen: true,
  toggleLeftSidebar: () =>
    set((s) => ({ leftSidebarOpen: !s.leftSidebarOpen })),

  rightPanelMode: "hidden",
  rightPanelPayload: null,
  openDetailPanel: (nodeId) =>
    set({ rightPanelMode: "detail", rightPanelPayload: { nodeId }, leftSidebarOpen: false }),
  openEditPanel: (nodeId, formType) =>
    set({ rightPanelMode: "edit", rightPanelPayload: { nodeId, formType }, leftSidebarOpen: false }),
  openPostPanel: () =>
    set({ rightPanelMode: "edit", rightPanelPayload: { nodeId: "", formType: "post" }, leftSidebarOpen: false }),
  openReviewPanel: () =>
    set({ rightPanelMode: "review", rightPanelPayload: null, leftSidebarOpen: false }),
  closeRightPanel: () =>
    set({ rightPanelMode: "hidden", rightPanelPayload: null, leftSidebarOpen: true }),

  headerExpanded: false,
  setHeaderExpanded: (v) => set({ headerExpanded: v }),

  zoomLevel: 1,
  setZoomLevel: (zoom) => set({ zoomLevel: zoom }),
  canvasMode: "lineage",
  setCanvasMode: (canvasMode) => set({ canvasMode }),

  // Viewport persistence
  viewports: {
    lineage: { ...DEFAULT_VIEWPORT },
    explore: { ...DEFAULT_VIEWPORT },
  },
  saveViewport: (mode, viewport) =>
    set((s) => ({
      viewports: { ...s.viewports, [mode]: viewport },
    })),

  // Toast
  toasts: [],
  addToast: (toast) => {
    let msg = toast.message;
    if (toast.type === "error") {
      if (
        msg.includes("Failed to fetch") ||
        msg.includes("NetworkError") ||
        msg.includes("network") ||
        msg.includes("ECONNREFUSED")
      ) {
        msg = "无法连接到服务器，请检查网络或后端状态。";
      }
    }

    const { toasts } = get();
    if (toasts.some((t) => t.type === toast.type && t.message === msg)) {
      return;
    }

    const id = String(++toastId);
    set((s) => ({ toasts: [...s.toasts, { ...toast, message: msg, id }] }));
    const duration = toast.type === "error" ? 5000 : 3000;
    setTimeout(() => {
      set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) }));
    }, duration);
  },
  removeToast: (id) =>
    set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })),

  // Node detail tabs
  activeNodeTab: "details",
  setActiveNodeTab: (tab) => set({ activeNodeTab: tab }),

  // Mobile
  isMobileMenuOpen: false,
  setMobileMenuOpen: (v) => set({ isMobileMenuOpen: v }),
}));
