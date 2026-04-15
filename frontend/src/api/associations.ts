import { request } from "./client";
import type {
  AssociationResult,
  CreateAssociationRequest,
} from "./types";

export const createAssociation = (body: CreateAssociationRequest) =>
  request<AssociationResult>("/api/associations", {
    method: "POST",
    body: JSON.stringify(body),
  });

export const deleteAssociation = (associationId: string) =>
  request<{ association_id: string; deleted: boolean }>(
    `/api/associations/${associationId}`,
    { method: "DELETE" },
  );
