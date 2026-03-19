import { request } from "./client";
import type {
  AuditDetail,
  AuditListResponse,
  DecisionType,
} from "./types";

interface AuditQueryParams {
  type?: DecisionType;
  operator_id?: string;
  since?: string;
  until?: string;
  after?: string;
  limit?: number;
}

export const fetchAuditList = (params: AuditQueryParams = {}) => {
  const qs = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v != null) qs.set(k, String(v));
  });
  const q = qs.toString();
  return request<AuditListResponse>(
    `/api/audit/decisions${q ? `?${q}` : ""}`,
  );
};

export const fetchAuditDetail = (decisionId: string) =>
  request<AuditDetail>(`/api/audit/decisions/${decisionId}`);
