import { request } from "./client";
import type { ReviewTaskPayload } from "./types";

export const fetchPendingReviews = (limit?: number) => {
  const qs = limit != null ? `?limit=${limit}` : "";
  return request<ReviewTaskPayload[]>(`/api/reviews/pending${qs}`);
};

export const fetchReviewDetail = (id: string) =>
  request<ReviewTaskPayload>(`/api/reviews/${id}`);

export const approveMerge = (id: string) =>
  request<void>(`/api/reviews/${id}/approve-merge`, { method: "POST" });

export const approveBranch = (id: string) =>
  request<void>(`/api/reviews/${id}/approve-branch`, { method: "POST" });

export const rejectReview = (id: string) =>
  request<void>(`/api/reviews/${id}/reject`, { method: "POST" });
