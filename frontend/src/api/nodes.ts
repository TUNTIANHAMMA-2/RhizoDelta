import { request } from "./client";
import type {
  AssociationInfo,
  AssociationType,
  GraphNodeDTO,
  GraphTopologyDTO,
  EmbeddingWriteRequest,
} from "./types";

export const fetchNode = (id: string) =>
  request<GraphNodeDTO>(`/api/nodes/${id}`);

export const fetchRhizomes = (limit?: number) => {
  const qs = limit ? `?limit=${limit}` : "";
  return request<GraphNodeDTO[]>(`/api/nodes/roots${qs}`);
};

export const fetchLineage = (id: string, maxDepth?: number) =>
  request<GraphTopologyDTO>(
    `/api/nodes/${id}/lineage${maxDepth ? `?max_depth=${maxDepth}` : ""}`,
  );

export const fetchChildren = (
  id: string,
  maxDepth?: number,
  limit?: number,
) => {
  const params = new URLSearchParams();
  if (maxDepth) params.set("max_depth", String(maxDepth));
  if (limit) params.set("limit", String(limit));
  const qs = params.toString();
  return request<GraphTopologyDTO>(
    `/api/nodes/${id}/children${qs ? `?${qs}` : ""}`,
  );
};

export const fetchProvenance = (id: string) =>
  request<GraphNodeDTO[]>(`/api/nodes/${id}/provenance`);

export const fetchAssociations = (
  id: string,
  type?: AssociationType,
  limit?: number,
) => {
  const params = new URLSearchParams();
  if (type) params.set("type", type);
  if (limit) params.set("limit", String(limit));
  const qs = params.toString();
  return request<AssociationInfo[]>(
    `/api/nodes/${id}/associations${qs ? `?${qs}` : ""}`,
  );
};

export const writeEmbedding = (id: string, body: EmbeddingWriteRequest) =>
  request<{ node_id: string; dimension: number }>(
    `/api/nodes/${id}/embedding`,
    {
      method: "PUT",
      body: JSON.stringify(body),
    },
  );

export const summarizeNode = (id: string) =>
  request<{ summary: string; source_count: number; model_used: string }>(
    `/api/nodes/${id}/summarize`,
    { method: "POST" },
  );
