import { create } from "zustand";

export interface AuthState {
  token: string | null;
  roles: string[];

  setToken: (token: string) => void;
  clearToken: () => void;

  isAuthenticated: () => boolean;
  hasRole: (role: string) => boolean;
}

export const useAuthStore = create<AuthState>((set, get) => ({
  token: localStorage.getItem("jwt_token"),
  roles: (() => {
    const t = localStorage.getItem("jwt_token");
    if (!t) return [];
    try {
      const base64Url = t.split(".")[1];
      const base64 = base64Url.replace(/-/g, "+").replace(/_/g, "/");
      const payload = JSON.parse(atob(base64));
      return payload.roles ?? [];
    } catch {
      return [];
    }
  })(),

  setToken: (token: string) => {
    localStorage.setItem("jwt_token", token);
    try {
      const base64Url = token.split(".")[1];
      const base64 = base64Url.replace(/-/g, "+").replace(/_/g, "/");
      const payload = JSON.parse(atob(base64));
      set({ token, roles: payload.roles ?? [] });
    } catch {
      set({ token, roles: [] });
    }
  },

  clearToken: () => {
    localStorage.removeItem("jwt_token");
    set({ token: null, roles: [] });
  },

  isAuthenticated: () => get().token !== null,
  hasRole: (role: string) => get().roles.includes(role),
}));
