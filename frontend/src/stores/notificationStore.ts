import { create } from "zustand";

export interface NotificationItem {
  id: string;
  type:
    | "node_created"
    | "edge_created"
    | "decision_complete"
    | "orchestration_status"
    | "summary_generated"
    | "quality_scored";
  nodeId?: string;
  message: string;
  timestamp: string;
  read: boolean;
}

const MAX_ITEMS = 100;

export interface NotificationState {
  items: NotificationItem[];
  unreadCount: number;
  addNotification: (item: Omit<NotificationItem, "id" | "read">) => void;
  markRead: (id: string) => void;
  markAllRead: () => void;
  clear: () => void;
}

let notificationId = 0;

export const useNotificationStore = create<NotificationState>((set) => ({
  items: [],
  unreadCount: 0,

  addNotification: (item) => {
    const id = String(++notificationId);
    set((s) => {
      // Merge orchestration_status notifications for the same nodeId
      if (item.type === "orchestration_status" && item.nodeId) {
        const existingIdx = s.items.findIndex(
          (n) => n.type === "orchestration_status" && n.nodeId === item.nodeId && !n.read,
        );
        if (existingIdx >= 0) {
          const items = [...s.items];
          items[existingIdx] = { ...items[existingIdx], message: item.message, timestamp: item.timestamp };
          return { items, unreadCount: items.filter((n) => !n.read).length };
        }
      }
      const items = [{ ...item, id, read: false }, ...s.items].slice(
        0,
        MAX_ITEMS,
      );
      return { items, unreadCount: items.filter((n) => !n.read).length };
    });
  },

  markRead: (id) =>
    set((s) => {
      const items = s.items.map((n) =>
        n.id === id ? { ...n, read: true } : n,
      );
      return { items, unreadCount: items.filter((n) => !n.read).length };
    }),

  markAllRead: () =>
    set((s) => ({
      items: s.items.map((n) => ({ ...n, read: true })),
      unreadCount: 0,
    })),

  clear: () => set({ items: [], unreadCount: 0 }),
}));
