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
      const payload = JSON.parse(atob(t.split(".")[1]));
      return payload.roles ?? [];
    } catch {
      return [];
    }
  })(),

  setToken: (token: string) => {
    localStorage.setItem("jwt_token", token);
    try {
      const payload = JSON.parse(atob(token.split(".")[1]));
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
