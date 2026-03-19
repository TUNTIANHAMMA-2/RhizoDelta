import { request } from "./client";
import type {
  SimilaritySearchRequest,
  SimilaritySearchResult,
} from "./types";

export const searchSimilar = (body: SimilaritySearchRequest) =>
  request<SimilaritySearchResult[]>("/api/nodes/search/similar", {
    method: "POST",
    body: JSON.stringify(body),
  });
