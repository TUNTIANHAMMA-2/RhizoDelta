import type { ApiResponse } from "./types";
import { useAuthStore } from "../stores/authStore";

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "";

export async function request<T>(
  path: string,
  options?: RequestInit,
): Promise<T> {
  const token = localStorage.getItem("jwt_token");
  const res = await fetch(`${BASE_URL}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...options?.headers,
    },
  });

  // Handle non-OK responses
  if (!res.ok) {
    if (res.status === 401) {
      useAuthStore.getState().clearToken();
    }
    const text = await res.text().catch(() => "");
    throw new Error(
      text || `Request failed: ${res.status} ${res.statusText}`,
    );
  }

  // Handle empty body (e.g. 202 Accepted, 204 No Content)
  const text = await res.text();
  if (!text) {
    return undefined as T;
  }

  // Parse JSON
  let body: ApiResponse<T>;
  try {
    body = JSON.parse(text);
  } catch {
    throw new Error(`Invalid JSON response from ${path}`);
  }

  if (body.code !== 0) {
    throw new Error(body.message || `API error: code ${body.code}`);
  }
  return body.data;
}
