import { request } from "./client";
import type {
  AssociationInfo,
  AssociationType,
  DiscussionTreeResponse,
  GraphNodeDTO,
  GraphTopologyDTO,
  TopologyContextDTO,
  EmbeddingWriteRequest,
} from "./types";

export const fetchNode = (id: string) =>
  request<GraphNodeDTO>(`/api/nodes/${id}`);

export const fetchEmbedding = (id: string) =>
  request<{ node_id: string; vector: number[]; dimension: number }>(
    `/api/nodes/${id}/embedding`,
  );

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

/**
 * 一次 HTTP 拉回 lineage + children，替代 `fetchLineage` 后串行 `fetchChildren`
 * 的瀑布，节省一次跨网络 RTT。后端在同一只读事务里顺序跑两条查询；叶子节点
 * 的 children 会降级为空（不报错）。
 */
export const fetchTopologyContext = (
  id: string,
  options?: {
    lineageDepth?: number;
    childrenDepth?: number;
    childrenLimit?: number;
  },
) => {
  const params = new URLSearchParams();
  if (options?.lineageDepth)
    params.set("lineage_depth", String(options.lineageDepth));
  if (options?.childrenDepth)
    params.set("children_depth", String(options.childrenDepth));
  if (options?.childrenLimit)
    params.set("children_limit", String(options.childrenLimit));
  const qs = params.toString();
  return request<TopologyContextDTO>(
    `/api/nodes/${id}/topology-context${qs ? `?${qs}` : ""}`,
  );
};

export const fetchDiscussionTree = (
  rootId: string,
  params?: { maxDepth?: number; limit?: number; cursor?: string },
) => {
  const qs = new URLSearchParams();
  if (params?.maxDepth) qs.set("max_depth", String(params.maxDepth));
  if (params?.limit) qs.set("limit", String(params.limit));
  if (params?.cursor) qs.set("cursor", params.cursor);
  const q = qs.toString();
  return request<DiscussionTreeResponse>(
    `/api/nodes/${rootId}/discussion-tree${q ? `?${q}` : ""}`,
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
