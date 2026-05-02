import { request } from "./client";
import type {
  MuteCreatedResponse,
  MuteItem,
  MuteListResponse,
  MuteRequest,
} from "./types";

export type { MuteCreatedResponse, MuteItem, MuteListResponse };

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
