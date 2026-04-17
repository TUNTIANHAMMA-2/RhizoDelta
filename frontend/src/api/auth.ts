import { request } from "./client";

interface UserPayload {
  user_id: string;
  username: string;
  display_name: string;
  roles: string[];
}

export const fetchMe = () => request<UserPayload>("/api/auth/me");
