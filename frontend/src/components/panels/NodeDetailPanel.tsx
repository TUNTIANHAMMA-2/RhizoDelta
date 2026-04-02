import { useState } from "react";
import { useUiStore, type NodeTab } from "../../stores/uiStore";
import { useGraphStore } from "../../stores/graphStore";
import { useSseStore } from "../../stores/sseStore";
import { ProvenancePanel } from "./ProvenancePanel";
import { AssociationPanel } from "./AssociationPanel";
import { AuditPanel } from "./AuditPanel";
import { MarkdownViewer } from "../editor/MarkdownViewer";
import { DecisionCard } from "./DecisionCard";
import { summarizeNode } from "../../api/nodes";
import type { DecisionExplanation } from "../../api/types";

const TABS: { id: NodeTab; label: string }[] = [
  { id: "details", label: "详情" },
  { id: "provenance", label: "确权溯源" },
  { id: "association", label: "关联" },
  { id: "audit", label: "审计" },
];

const TYPE_COLOR = {
  Human_Post: "var(--color-node-human)",
  AI_Consensus: "var(--color-node-consensus)",
  Result: "var(--color-node-result)",
} as const;

const ORCHESTRATION_STATUS_LABELS: Record<string, string> = {
  POST_ACCEPTED: "已进入发布队列",
  EMBEDDING_READY: "内容索引已完成",
  EVALUATION_STARTED: "AI 正在判断与目标帖的关系",
  RECALL_COMPLETED: "上下文召回已完成",
  MERGE_QUEUED: "AI 已判定并入共识",
  BRANCH_QUEUED: "AI 已判定分出新支线",
  REVIEW_PENDING: "等待人工复核",
  REFLECTION_STARTED: "AI 正在反思决策",
  REFLECTION_CONFIRMED: "AI 反思已确认决策",
  REFLECTION_REVISED: "AI 反思后修正了决策",
  REFLECTION_EXHAUSTED: "AI 反思次数已耗尽，转为人工复核",
  FAILED: "处理失败",
};

export function NodeDetailPanel() {
  const payload = useUiStore((s) => s.rightPanelPayload);
  const closePanel = useUiStore((s) => s.closeRightPanel);
  const activeTab = useUiStore((s) => s.activeNodeTab);
  const setActiveTab = useUiStore((s) => s.setActiveNodeTab);
  const nodes = useGraphStore((s) => s.nodes);
  const orchestrationStatuses = useSseStore((s) => s.orchestrationStatuses);
  const [summarizing, setSummarizing] = useState(false);

  if (!payload) return null;
  const node = nodes.get(payload.nodeId);
  if (!node) return null;

  const orchestrationStatus = orchestrationStatuses[node.node_id];
  const statusLabel = orchestrationStatus
    ? ORCHESTRATION_STATUS_LABELS[orchestrationStatus.status] ?? orchestrationStatus.status
    : null;

  let parsedExplanation: DecisionExplanation | null = null;
  if (orchestrationStatus?.explanation) {
    try {
      parsedExplanation = JSON.parse(orchestrationStatus.explanation) as DecisionExplanation;
    } catch {
      // ignore parse errors
    }
  }

  return (
    <aside
      className="rd-panel"
      onWheel={(e) => e.stopPropagation()}
      onTouchMove={(e) => e.stopPropagation()}
      style={{
        width: "45vw",
        minWidth: 460,
        position: "relative",
        borderLeft: "1px solid var(--color-border-default)",
        background: "var(--color-bg-primary)",
        display: "flex",
        flexDirection: "column",
        fontFamily: "var(--font-ui)",
        height: "100%",
      }}
    >
      <div className="rd-marker-selected" style={{ top: 20, left: 16 }} />
      {/* Header */}
      <div
        style={{
          padding: "var(--space-4)",
          borderBottom: "1px solid var(--color-border-default)",
        }}
      >
        <div
          style={{
            display: "flex",
            justifyContent: "space-between",
            alignItems: "center",
            marginBottom: "var(--space-2)",
          }}
        >
          <div style={{ display: "flex", alignItems: "center", gap: "var(--space-2)", marginLeft: 36 }}>
            <span
              style={{
                width: 8,
                height: 8,
                borderRadius: "var(--radius-full)",
                background: TYPE_COLOR[node.label] ?? "var(--color-text-tertiary)",
              }}
            />
            <span style={{ fontWeight: 600, fontSize: "var(--font-size-md)" }}>
              {node.label.replace("_", " ")}
            </span>
          </div>
          <button
            onClick={closePanel}
            aria-label="关闭面板"
            style={{
              background: "none",
              border: "none",
              cursor: "pointer",
              fontSize: "var(--font-size-md)",
              color: "var(--color-text-secondary)",
            }}
          >
            &times;
          </button>
        </div>
        <div
          style={{
            fontSize: "var(--font-size-xs)",
            color: "var(--color-text-secondary)",
          }}
        >
          {node.author_id ?? node.agent_version ?? "System"} &middot;{" "}
          {new Date(node.created_at).toLocaleString()}
        </div>
      </div>

      {/* Tabs */}
      <div
        role="tablist"
        aria-label="节点详情分类"
        style={{
          display: "flex",
          borderBottom: "1px solid var(--color-border-default)",
          padding: "0 var(--space-4)",
        }}
      >
        {TABS.map((tab) => (
          <button
            key={tab.id}
            role="tab"
            aria-selected={activeTab === tab.id}
            aria-controls={`tabpanel-${tab.id}`}
            id={`tab-${tab.id}`}
            onClick={() => setActiveTab(tab.id)}
            style={{
              padding: "var(--space-3) var(--space-3)",
              background: "none",
              border: "none",
              cursor: "pointer",
              borderBottom:
                activeTab === tab.id
                  ? "2px solid var(--color-accent)"
                  : "2px solid transparent",
              color:
                activeTab === tab.id
                  ? "var(--color-text-primary)"
                  : "var(--color-text-tertiary)",
              fontFamily: "var(--font-ui)",
              fontSize: "var(--font-size-xs)",
              fontWeight: activeTab === tab.id ? 600 : 400,
              transition: "var(--transition-fast)",
            }}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Content */}
      <div
        id={`tabpanel-${activeTab}`}
        role="tabpanel"
        aria-labelledby={`tab-${activeTab}`}
        style={{ flex: 1, overflowY: "auto", padding: "var(--space-4)" }}
      >
        {activeTab === "details" && (
          <>
            <div style={{ marginBottom: "var(--space-6)" }}>
              {node.content || node.summary_content ? (
                <MarkdownViewer content={node.content ?? node.summary_content ?? ""} />
              ) : (
                <div style={{
                  fontFamily: "var(--font-content)",
                  fontSize: "var(--font-size-base)",
                  color: "var(--color-text-tertiary)",
                  fontStyle: "italic",
                }}>
                  No content
                </div>
              )}
            </div>
            <div
              style={{
                fontSize: "var(--font-size-xs)",
                color: "var(--color-text-secondary)",
                borderTop: "1px solid var(--color-border-default)",
                paddingTop: "var(--space-3)",
                display: "flex",
                flexDirection: "column",
                gap: "var(--space-1)",
              }}
            >
              <div>node_id: {node.node_id}</div>
              <div>has_embedding: {String(node.has_embedding)}</div>
              {node.operation_id && <div>operation_id: {node.operation_id}</div>}
              {node.quality_overall != null && (
                <div>quality_overall: {(node.quality_overall * 100).toFixed(0)}%</div>
              )}
              {node.label === "AI_Consensus" && (
                <button
                  onClick={() => {
                    setSummarizing(true);
                    summarizeNode(node.node_id)
                      .then(() => setSummarizing(false))
                      .catch(() => setSummarizing(false));
                  }}
                  disabled={summarizing}
                  style={{
                    marginTop: "var(--space-2)",
                    padding: "var(--space-1) var(--space-3)",
                    fontSize: "var(--font-size-xs)",
                    cursor: summarizing ? "not-allowed" : "pointer",
                    background: "var(--color-bg-secondary)",
                    border: "1px solid var(--color-border-default)",
                    borderRadius: "var(--radius-md, 4px)",
                    color: "var(--color-text-primary)",
                    opacity: summarizing ? 0.6 : 1,
                  }}
                >
                  {summarizing ? "生成中..." : "生成摘要"}
                </button>
              )}
              
              <div style={{ marginTop: "var(--space-4)", paddingTop: "var(--space-2)", borderTop: "1px dashed var(--color-border-default)" }}>
                <div style={{ fontWeight: 600, marginBottom: "var(--space-2)", fontSize: "var(--font-size-sm)" }}>编排状态</div>
                {orchestrationStatus ? (
                  <div style={{ display: "flex", flexDirection: "column", gap: "var(--space-1)" }}>
                    <div style={{ display: "flex", gap: "var(--space-2)" }}>
                      <span style={{ color: "var(--color-text-secondary)" }}>状态:</span>
                      <span style={{ 
                        color: orchestrationStatus.status === "FAILED" ? "var(--color-danger)" :
                               orchestrationStatus.status === "EMBEDDING_READY" ? "var(--color-success)" :
                               "var(--color-text-primary)",
                        fontWeight: 600
                      }}>
                        {statusLabel}
                      </span>
                    </div>
                    {orchestrationStatus.message && (
                      <div style={{ color: "var(--color-text-secondary)", fontSize: "var(--font-size-xs)" }}>
                        {orchestrationStatus.message}
                      </div>
                    )}
                  </div>
                ) : (
                  <div style={{ color: "var(--color-text-tertiary)", fontStyle: "italic" }}>
                    暂无编排状态
                  </div>
                )}
              </div>
              {parsedExplanation && <DecisionCard explanation={parsedExplanation} />}
            </div>
          </>
        )}
        {activeTab === "provenance" && (
          <ProvenancePanel nodeId={payload.nodeId} />
        )}
        {activeTab === "association" && (
          <AssociationPanel nodeId={payload.nodeId} />
        )}
        {activeTab === "audit" && <AuditPanel nodeId={payload.nodeId} />}
      </div>
    </aside>
  );
}
