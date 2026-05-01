import { request } from "./client";
import type { UserProfile } from "./types";

export async function getMyProfile(): Promise<UserProfile> {
  return request<UserProfile>("/api/users/me/profile");
}

export async function updateProfile(fields: Record<string, string | null>): Promise<UserProfile> {
  return request<UserProfile>("/api/users/me/profile", {
    method: "PUT",
    body: JSON.stringify(fields),
  });
}

export async function uploadAvatar(file: File): Promise<UserProfile> {
  const formData = new FormData();
  formData.append("file", file);
  return request<UserProfile>("/api/users/me/avatar", {
    method: "PUT",
    body: formData,
  });
}

export async function deleteAvatar(): Promise<void> {
  await request<void>("/api/users/me/avatar", { method: "DELETE" });
}
