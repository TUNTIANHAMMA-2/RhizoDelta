import { create } from "zustand";

interface JwtPayload {
  sub?: string;
  roles?: string[];
}

function readStoredToken(): string | null {
  if (typeof localStorage === "undefined") {
    return null;
  }
  return localStorage.getItem("jwt_token");
}

export interface AuthState {
  token: string | null;
  userId: string | null;
  roles: string[];

  setToken: (token: string) => void;
  clearToken: () => void;

  isAuthenticated: () => boolean;
  hasRole: (role: string) => boolean;
}

function parseJwtPayload(token: string | null): JwtPayload {
  if (!token) return {};
  try {
    const base64Url = token.split(".")[1];
    const base64 = base64Url.replace(/-/g, "+").replace(/_/g, "/");
    return JSON.parse(atob(base64)) as JwtPayload;
  } catch {
    return {};
  }
}

export const useAuthStore = create<AuthState>((set, get) => ({
  token: readStoredToken(),
  userId: parseJwtPayload(readStoredToken()).sub ?? null,
  roles: parseJwtPayload(readStoredToken()).roles ?? [],

  setToken: (token: string) => {
    if (typeof localStorage !== "undefined") {
      localStorage.setItem("jwt_token", token);
    }
    const payload = parseJwtPayload(token);
    set({ token, userId: payload.sub ?? null, roles: payload.roles ?? [] });
  },

  clearToken: () => {
    if (typeof localStorage !== "undefined") {
      localStorage.removeItem("jwt_token");
    }
    set({ token: null, userId: null, roles: [] });
  },

  isAuthenticated: () => get().token !== null,
  hasRole: (role: string) => get().roles.includes(role),
}));
