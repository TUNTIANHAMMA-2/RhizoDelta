/** 后端统一响应结构 */
export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

// ────────────────────── 图谱基础类型 ──────────────────────

export type NodeLabel = "Human_Post" | "AI_Consensus" | "Result";

export type EvolutionEdgeType =
  | "BRANCHED_FROM"
  | "MERGED_INTO"
  | "SYNTHESIZED_FROM"
  | "CONTINUES_FROM"
  | "CONVERGED_FROM"
  | "MATERIALIZED_FROM"
  | "CROSS_SYNTHESIZED_FROM";

export type SemanticEdgeType = "CONCEPTUAL_OVERLAP" | "RELATES_TO";

export type DecisionType =
  | "MERGE"
  | "BRANCH"
  | "INJECT"
  | "MATERIALIZE"
  | "FORK"
  | "CROSS_SYNTH"
  | "JOIN";

export interface GraphNodeDTO {
  node_id: string;
  label: NodeLabel;
  content: string | null;
  summary_content: string | null;
  author_id: string | null;
  agent_version: string | null;
  operation_id: string | null;
  created_at: string;
  has_embedding: boolean;
  quality_overall?: number | null;
}

export interface GraphEdgeDTO {
  source: string;
  target: string;
  type: EvolutionEdgeType;
  created_at: string;
}

export interface GraphTopologyDTO {
  nodes: GraphNodeDTO[];
  edges: GraphEdgeDTO[];
}

// ────────────────────── SSE 事件 ──────────────────────

export interface NodeCreatedEvent {
  node_id: string;
  label: NodeLabel;
  created_at: string;
}

export interface EdgeCreatedEvent {
  source: string;
  target: string;
  type: EvolutionEdgeType;
  created_at: string;
}

export interface EdgeRemovedEvent {
  source: string;
  type: string;
}

export interface DecisionCompleteEvent {
  decision_id: string;
  decision_type: DecisionType;
  node_id: string;
}

export interface SummaryGeneratedEvent {
  node_id: string;
  summary: string;
  source_count: number;
  model_used: string;
}

export interface QualityScoredEvent {
  node_id: string;
  quality_overall: number;
  quality_relevance: number;
  quality_density: number;
  quality_argumentation: number;
  quality_community_value: number;
  reason: string;
}

export interface DecisionExplanation {
  action: string;
  confidence: number;
  reason: string;
  candidateComparison: string;
  reflectionSummary: string;
}

export interface OrchestrationStatusEvent {
  request_id: string;
  event_id: string;
  post_node_id: string;
  status: string;
  message: string;
  review_id?: string | null;
  explanation?: string | null;
}

// ────────────────────── 发帖 ──────────────────────

export interface CreatePostRequest {
  request_id: string;
  author_id: string;
  content: string;
  target_node_id?: string;
}

export interface PostAcceptedResponse {
  event_id: string;
  status: string;
}

// ────────────────────── 语义关联 ──────────────────────

export type AssociationType = "CONCEPTUAL_OVERLAP" | "RELATES_TO";

export interface CreateAssociationRequest {
  source_node_id: string;
  target_node_id: string;
  type: AssociationType;
  creator_id: string;
  reason: string;
  confidence: number;
}

export interface AssociationResult {
  association_id: string;
  source_node_id: string;
  target_node_id: string;
  type: AssociationType;
  confidence: number;
  reason: string;
  creator_id: string;
  created_at: string;
}

export interface AssociationInfo {
  association_id: string;
  type: AssociationType;
  direction: "OUTGOING" | "INCOMING";
  related_node: {
    node_id: string;
    label: NodeLabel;
    content: string | null;
    summary_content: string | null;
  };
  confidence: number;
  reason: string;
  creator_id: string;
  created_at: string;
}

// ────────────────────── 审计 ──────────────────────

export interface AuditRecord {
  decision_id: string;
  decision_type: DecisionType;
  node_id: string;
  source_node_id: string;
  operator_type: "AGENT" | "HUMAN";
  operator_id: string;
  reason: string;
  created_at: string;
}

export interface AuditDetail extends AuditRecord {
  synthesized_from?: string[];
}

export interface AuditListResponse {
  records: AuditRecord[];
  next_cursor: string | null;
}

// ────────────────────── 向量搜索 ──────────────────────

export interface EmbeddingWriteRequest {
  vector: number[];
}

export interface SimilaritySearchRequest {
  vector: number[];
  top_k?: number;
}

export interface SimilaritySearchResult {
  node_id: string;
  label: NodeLabel;
  score: number;
  content: string | null;
  created_at: string;
  neighbors: {
    node_id: string;
    label: string;
    relationship_type: string;
  }[];
}

// ────────────────────── 决策命令 ──────────────────────

export interface DecisionAuditFields {
  operator_type: "AGENT" | "HUMAN";
  operator_id: string;
  reason: string;
}

export interface DecisionResult {
  decision_id: string;
  node_id: string;
  status: string;
}

export interface ForkDecisionResult {
  operation_id: string;
  node_ids: string[];
  status: string;
  created_count: number;
  total_count: number;
}

export interface RollbackResult {
  decision_id: string;
  rolled_back_node_id: string;
  relationships_removed: number;
  soft_deleted: boolean;
}

export interface ForkRollbackResult {
  operation_id: string;
  rolled_back_node_ids: string[];
  relationships_removed: number;
  soft_deleted: boolean;
  deleted_count: number;
}
