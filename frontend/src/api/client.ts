import type { ApiResponse } from "./types";

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
  const body: ApiResponse<T> = await res.json();
  if (body.code !== 0) {
    throw new Error(body.message);
  }
  return body.data;
}
