import { request } from "./client";
import type { CreatePostRequest, PostAcceptedResponse } from "./types";

export const createPost = (body: CreatePostRequest) =>
  request<PostAcceptedResponse>("/api/posts", {
    method: "POST",
    body: JSON.stringify(body),
  });
