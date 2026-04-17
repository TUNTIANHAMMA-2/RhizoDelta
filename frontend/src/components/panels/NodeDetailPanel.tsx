import { useState } from "react";
import clsx from "clsx";
import { useUiStore, type NodeTab } from "../../stores/uiStore";
import { useGraphStore } from "../../stores/graphStore";
import { useSseStore } from "../../stores/sseStore";
import { ProvenancePanel } from "./ProvenancePanel";
import { AssociationPanel } from "./AssociationPanel";
import { AuditPanel } from "./AuditPanel";
import { MarkdownViewer } from "../editor/MarkdownViewer";
import { DecisionCard } from "./DecisionCard";
import { summarizeNode, fetchNode } from "../../api/nodes";
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

function statusColor(status: string | undefined): string {
  if (status === "FAILED") return "var(--color-danger)";
  if (status === "EMBEDDING_READY") return "var(--color-success)";
  return "var(--color-text-primary)";
}

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
      className="rd-panel w-[45vw] min-w-[460px] relative border-l border-border-default bg-bg-primary flex flex-col font-ui h-full"
      onWheel={(e) => e.stopPropagation()}
      onTouchMove={(e) => e.stopPropagation()}
    >
      <div className="rd-marker-selected" style={{ top: 20, left: 16 }} />

      {/* Header */}
      <div className="p-4 border-b border-border-default">
        <div className="flex justify-between items-center mb-2">
          <div className="flex items-center gap-2 ml-9">
            <span
              className="w-2 h-2 rounded-full"
              style={{ background: TYPE_COLOR[node.label] ?? "var(--color-text-tertiary)" }}
            />
            <span className="font-semibold text-md">
              {node.label.replace("_", " ")}
            </span>
          </div>
          <button
            onClick={closePanel}
            aria-label="关闭面板"
            className="bg-transparent border-none cursor-pointer text-md text-text-secondary"
          >
            &times;
          </button>
        </div>
        <div className="text-xs text-text-secondary">
          {node.author_id ?? node.agent_version ?? "System"} &middot;{" "}
          {new Date(node.created_at).toLocaleString()}
        </div>
      </div>

      {/* Tabs */}
      <div
        role="tablist"
        aria-label="节点详情分类"
        className="flex border-b border-border-default px-4"
      >
        {TABS.map((tab) => {
          const active = activeTab === tab.id;
          return (
            <button
              key={tab.id}
              role="tab"
              aria-selected={active}
              aria-controls={`tabpanel-${tab.id}`}
              id={`tab-${tab.id}`}
              onClick={() => setActiveTab(tab.id)}
              className={clsx(
                "px-3 py-3 bg-transparent border-none cursor-pointer font-ui text-xs transition-[all] duration-[var(--transition-fast)] border-b-2",
                active
                  ? "border-accent text-text-primary font-semibold"
                  : "border-transparent text-text-tertiary font-normal",
              )}
            >
              {tab.label}
            </button>
          );
        })}
      </div>

      {/* Content */}
      <div
        id={`tabpanel-${activeTab}`}
        role="tabpanel"
        aria-labelledby={`tab-${activeTab}`}
        className="flex-1 overflow-y-auto p-4"
      >
        {activeTab === "details" && (
          <>
            <div className="mb-6">
              {node.content || node.summary_content ? (
                <MarkdownViewer content={node.content ?? node.summary_content ?? ""} />
              ) : (
                <div className="font-content text-base text-text-tertiary italic">
                  No content
                </div>
              )}
            </div>
            <div className="text-xs text-text-secondary border-t border-border-default pt-3 flex flex-col gap-1">
              <div>node_id: {node.node_id}</div>
              <div>has_embedding: {String(node.has_embedding)}</div>
              {node.quality_overall != null && (
                <div>quality_overall: {(node.quality_overall * 100).toFixed(0)}%</div>
              )}
              {node.label === "AI_Consensus" && (
                <button
                  onClick={() => {
                    setSummarizing(true);
                    summarizeNode(node.node_id)
                      .then(() => fetchNode(node.node_id))
                      .then((updated) => {
                        useGraphStore.getState().addNode(updated);
                      })
                      .catch(() => {})
                      .finally(() => setSummarizing(false));
                  }}
                  disabled={summarizing}
                  className={clsx(
                    "mt-2 px-3 py-1 text-xs bg-bg-secondary border border-border-default rounded-md text-text-primary",
                    summarizing ? "cursor-not-allowed opacity-60" : "cursor-pointer",
                  )}
                >
                  {summarizing ? "生成中..." : "生成摘要"}
                </button>
              )}

              <div className="mt-4 pt-2 border-t border-dashed border-border-default">
                <div className="font-semibold mb-2 text-sm">编排状态</div>
                {orchestrationStatus ? (
                  <div className="flex flex-col gap-1">
                    <div className="flex gap-2">
                      <span className="text-text-secondary">状态:</span>
                      <span
                        className="font-semibold"
                        style={{ color: statusColor(orchestrationStatus.status) }}
                      >
                        {statusLabel}
                      </span>
                    </div>
                    {orchestrationStatus.message && (
                      <div className="text-text-secondary text-xs">
                        {orchestrationStatus.message}
                      </div>
                    )}
                  </div>
                ) : (
                  <div className="text-text-tertiary italic">暂无编排状态</div>
                )}
              </div>
              {parsedExplanation && <DecisionCard explanation={parsedExplanation} />}
            </div>
          </>
        )}
        {activeTab === "provenance" && <ProvenancePanel nodeId={payload.nodeId} />}
        {activeTab === "association" && <AssociationPanel nodeId={payload.nodeId} />}
        {activeTab === "audit" && <AuditPanel nodeId={payload.nodeId} />}
      </div>
    </aside>
  );
}
