import { request } from "./client";
import type { MuteItem, MuteRequest } from "./types";

export interface MuteCreatedResponse {
  mute_id: string;
  target_type: string;
  target_id: string;
  since: string;
  reason: string;
  status: string;
}

export interface MuteListResponse {
  items: MuteItem[];
  page: number;
  size: number;
  total: number;
  total_pages: number;
  has_next: boolean;
}

export async function mute(data: MuteRequest): Promise<MuteCreatedResponse> {
  return request<MuteCreatedResponse>("/api/users/me/mutes", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export async function listMutes(page = 0, size = 20): Promise<MuteListResponse> {
  return request<MuteListResponse>(`/api/users/me/mutes?page=${page}&size=${size}`);
}

export async function unmute(muteId: string): Promise<void> {
  return request<void>(`/api/users/me/mutes/${encodeURIComponent(muteId)}`, {
    method: "DELETE",
  });
}
