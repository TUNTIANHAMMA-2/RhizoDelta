import { request } from "./client";
import type {
  FollowItem,
  FollowListResponse,
  FollowRequest,
} from "./types";

export interface FollowCreatedResponse {
  follow_id: string;
  target_type: string;
  target_id: string;
  since: string;
  status: string;
}

export async function follow(data: FollowRequest): Promise<FollowCreatedResponse> {
  return request<FollowCreatedResponse>("/api/users/me/follows", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export async function listFollows(page = 0, size = 20): Promise<FollowListResponse> {
  return request<FollowListResponse>(`/api/users/me/follows?page=${page}&size=${size}`);
}

export async function unfollow(followId: string): Promise<void> {
  return request<void>(`/api/users/me/follows/${encodeURIComponent(followId)}`, {
    method: "DELETE",
  });
}

export type { FollowItem };
