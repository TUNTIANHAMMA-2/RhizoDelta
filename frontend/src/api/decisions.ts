import { request } from "./client";
import type {
  DecisionAuditFields,
  DecisionResult,
  ForkDecisionResult,
  ForkRollbackResult,
  RollbackResult,
} from "./types";

export const executeMerge = (
  body: {
    decision_id: string;
    request_id: string;
    source_node_id: string;
    agent_version: string;
    summary_content: string;
    synthesized_from: string[];
  } & DecisionAuditFields,
) =>
  request<DecisionResult>("/api/decisions/merge", {
    method: "POST",
    body: JSON.stringify(body),
  });

export const executeBranch = (
  body: {
    decision_id: string;
    request_id: string;
    source_node_id: string;
    content: string;
    author_id: string;
  } & DecisionAuditFields,
) =>
  request<DecisionResult>("/api/decisions/branch", {
    method: "POST",
    body: JSON.stringify(body),
  });

export const executeInject = (
  body: {
    decision_id: string;
    request_id: string;
    source_node_id: string;
    content: string;
    author_id: string;
  } & DecisionAuditFields,
) =>
  request<DecisionResult>("/api/decisions/inject", {
    method: "POST",
    body: JSON.stringify(body),
  });

export const executeMaterialize = (
  body: {
    decision_id: string;
    request_id: string;
    source_node_id: string;
    content: string;
  } & DecisionAuditFields,
) =>
  request<DecisionResult>("/api/decisions/materialize", {
    method: "POST",
    body: JSON.stringify(body),
  });

export const executeFork = (
  body: {
    operation_id: string;
    request_id: string;
    source_node_id: string;
    branches: { decision_id: string; content: string; author_id: string }[];
  } & DecisionAuditFields,
) =>
  request<ForkDecisionResult>("/api/decisions/fork", {
    method: "POST",
    body: JSON.stringify(body),
  });

export const executeCrossSynth = (
  body: {
    decision_id: string;
    request_id: string;
    source_result_ids: string[];
    content: string;
  } & DecisionAuditFields,
) =>
  request<DecisionResult>("/api/decisions/cross-synth", {
    method: "POST",
    body: JSON.stringify(body),
  });

export const executeJoin = (
  body: {
    decision_id: string;
    request_id: string;
    source_node_ids: string[];
    summary_content: string;
    agent_version: string;
  } & DecisionAuditFields,
) =>
  request<DecisionResult>("/api/decisions/join", {
    method: "POST",
    body: JSON.stringify(body),
  });

export const rollbackDecision = (decisionId: string) =>
  request<RollbackResult>(`/api/decisions/${decisionId}/rollback`, {
    method: "POST",
  });

export const rollbackFork = (operationId: string) =>
  request<ForkRollbackResult>(
    `/api/decisions/fork/${operationId}/rollback`,
    { method: "POST" },
  );
