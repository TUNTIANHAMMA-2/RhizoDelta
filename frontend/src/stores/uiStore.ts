import { create } from "zustand";

export type RightPanelMode = "hidden" | "detail" | "edit" | "agent";
export type NodeTab = "details" | "provenance" | "association" | "audit";

export interface ToastMessage {
  id: string;
  type: "info" | "success" | "warning" | "error";
  message: string;
}

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
  closeRightPanel: () => void;

  headerExpanded: boolean;
  setHeaderExpanded: (v: boolean) => void;

  zoomLevel: number;
  setZoomLevel: (zoom: number) => void;

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

export const useUiStore = create<UiState>((set) => ({
  leftSidebarOpen: true,
  toggleLeftSidebar: () =>
    set((s) => ({ leftSidebarOpen: !s.leftSidebarOpen })),

  rightPanelMode: "hidden",
  rightPanelPayload: null,
  openDetailPanel: (nodeId) =>
    set({ rightPanelMode: "detail", rightPanelPayload: { nodeId } }),
  openEditPanel: (nodeId, formType) =>
    set({ rightPanelMode: "edit", rightPanelPayload: { nodeId, formType } }),
  openPostPanel: () =>
    set({ rightPanelMode: "edit", rightPanelPayload: { nodeId: "", formType: "post" } }),
  closeRightPanel: () =>
    set({ rightPanelMode: "hidden", rightPanelPayload: null }),

  headerExpanded: false,
  setHeaderExpanded: (v) => set({ headerExpanded: v }),

  zoomLevel: 1,
  setZoomLevel: (zoom) => set({ zoomLevel: zoom }),

  // Toast
  toasts: [],
  addToast: (toast) => {
    const id = String(++toastId);
    set((s) => ({ toasts: [...s.toasts, { ...toast, id }] }));
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
