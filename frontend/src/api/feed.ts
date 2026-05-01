import { request } from "./client";
import type { FeedResponse } from "./types";

export async function getFeed(page = 0, size = 50): Promise<FeedResponse> {
  return request<FeedResponse>(`/api/users/me/feed?page=${page}&size=${size}`);
}
