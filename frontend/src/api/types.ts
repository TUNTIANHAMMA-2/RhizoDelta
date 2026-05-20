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
  content?: string;
  summary_content?: string;
  author_id?: string;
  author_username?: string;
  author_display_name?: string;
  agent_version?: string;
  created_at: string;
  has_embedding: boolean;
  quality_overall?: number;
  is_following?: boolean;
  is_muted?: boolean;
  follow_id?: string | null;
  mute_id?: string | null;
}

export type HumanPostFeedItem = GraphNodeDTO & {
  label: "Human_Post";
  content: string;
};

export type AiConsensusFeedItem = GraphNodeDTO & {
  label: "AI_Consensus";
  summary_content: string;
};

export type ResultFeedItem = GraphNodeDTO & {
  label: "Result";
};

export type FeedItem = HumanPostFeedItem | AiConsensusFeedItem | ResultFeedItem;

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
  post_node_id?: string | null;
  status: string;
  message: string;
  review_id?: string | null;
  decision_id?: string | null;
  author_id?: string | null;
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
  operation_id?: string | null;
}

export interface AuditDetail extends AuditRecord {
  synthesized_from?: string[];
}

export interface AuditListResponse {
  records: AuditRecord[];
  next_cursor: string | null;
}

// ────────────────────── 人工复核 ──────────────────────

export interface ReviewTaskPayload {
  review_id: string;
  request_id: string;
  post_node_id: string;
  workflow_trace_id: string;
  status: string;
  suggested_action: string;
  candidate_node_ids: string[];
  draft_payload: Record<string, unknown>;
  review_reason_codes: string[];
  created_at: string;
  updated_at: string;
  expires_at: string;
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

// ────────────────────── 用户画像与个性化 ──────────────────────

export interface UserProfile {
  user_id: string;
  display_name: string;
  avatar_url: string | null;
  language: string | null;
  timezone: string | null;
  theme: string | null;
  notification_prefs: string | null;
  updated_at: string | null;
}

export interface PublicUserProfile {
  user_id: string;
  username?: string;
  display_name?: string;
  avatar_url?: string | null;
  status?: string;
}

export interface FollowItem {
  follow_id: string;
  target_type: string;
  target_id: string;
  target_display_name?: string;
  since: string;
}

export interface FollowListResponse {
  items: FollowItem[];
  page: number;
  size: number;
  total: number;
  total_pages: number;
  has_next: boolean;
}

export interface MuteItem {
  mute_id: string;
  target_type: string;
  target_id: string;
  target_display_name?: string;
  reason?: string;
  since: string;
}

export interface FeedResponse {
  items: FeedItem[];
  page: number;
  size: number;
  has_next: boolean;
}

/** POST /api/users/me/mutes 的响应载荷。 */
export interface MuteCreatedResponse {
  mute_id: string;
  target_type: string;
  target_id: string;
  since: string;
  reason: string;
  status: string;
}

/** GET /api/users/me/mutes 的响应载荷。 */
export interface MuteListResponse {
  items: MuteItem[];
  page: number;
  size: number;
  total: number;
  total_pages: number;
  has_next: boolean;
}

export interface OnlineStatus {
  user_id: string;
  online: boolean;
  last_active?: string;
}

export interface FollowRequest {
  target_type: string;
  target_id: string;
}

export interface MuteRequest {
  target_type: string;
  target_id: string;
  reason?: string;
}

export interface AuthSessionPayload {
  token: string;
  refresh_token: string;
  user: {
    user_id: string;
    username: string;
    display_name: string;
    roles: string[];
  };
}
