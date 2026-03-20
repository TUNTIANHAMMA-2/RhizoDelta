import { useEffect, useState } from "react";
import { useGraphStore } from "../../stores/graphStore";
import { useAuthStore } from "../../stores/authStore";
import { useUiStore } from "../../stores/uiStore";
import { deleteAssociation, createAssociation } from "../../api/associations";
import { Skeleton } from "../feedback/Skeleton";
import { EmptyState } from "../feedback/EmptyState";
import { ConfirmDialog } from "../modals/ConfirmDialog";
import type { AssociationInfo, AssociationType } from "../../api/types";

interface Props {
  nodeId: string;
}

export function AssociationPanel({ nodeId }: Props) {
  const loadAssociations = useGraphStore((s) => s.loadAssociations);
  const associations = useGraphStore((s) => s.associations);
  const selectNode = useGraphStore((s) => s.selectNode);
  const openDetailPanel = useUiStore((s) => s.openDetailPanel);
  const addToast = useUiStore((s) => s.addToast);
  const isAdmin = useAuthStore((s) => s.hasRole("ADMIN"));
  const canCreate = useAuthStore((s) => s.hasRole("AGENT") || s.hasRole("ADMIN"));
  const token = useAuthStore((s) => s.token);
  
  const [loading, setLoading] = useState(true);
  const [deleteTarget, setDeleteTarget] = useState<AssociationInfo | null>(null);

  const [showForm, setShowForm] = useState(false);
  const [targetId, setTargetId] = useState("");
  const [assocType, setAssocType] = useState<AssociationType>("RELATES_TO");
  const [reason, setReason] = useState("");
  const [confidence, setConfidence] = useState(1);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    setLoading(true);
    loadAssociations(nodeId).finally(() => setLoading(false));
  }, [nodeId, loadAssociations]);

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteAssociation(deleteTarget.association_id);
      addToast({ type: "success", message: "关联已删除" });
      setDeleteTarget(null);
      loadAssociations(nodeId);
    } catch {
      addToast({ type: "error", message: "删除失败" });
    }
  };

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!targetId.trim()) return;
    
    setSubmitting(true);
    try {
      let creatorId = "unknown";
      if (token) {
        try {
          creatorId = JSON.parse(atob(token.split(".")[1])).sub ?? "unknown";
        } catch {}
      }
      
      await createAssociation({
        source_node_id: nodeId,
        target_node_id: targetId.trim(),
        type: assocType,
        creator_id: creatorId,
        reason,
        confidence
      });
      addToast({ type: "success", message: "关联已创建" });
      setShowForm(false);
      setTargetId("");
      setReason("");
      loadAssociations(nodeId);
    } catch (err) {
      addToast({ type: "error", message: "创建失败: " + (err as Error).message });
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) {
    return (
      <div style={{ display: "flex", flexDirection: "column", gap: "var(--space-3)" }}>
        <Skeleton variant="rectangular" height={56} />
        <Skeleton variant="rectangular" height={56} />
      </div>
    );
  }

  return (
    <>
      <div style={{ display: "flex", flexDirection: "column", gap: "var(--space-3)" }}>
        {canCreate && (
          <div style={{ marginBottom: "var(--space-1)" }}>
            {!showForm ? (
              <button className="btn-secondary" style={{ width: "100%" }} onClick={() => setShowForm(true)}>
                + 添加语义关联
              </button>
            ) : (
              <form onSubmit={handleCreate} style={{ display: "flex", flexDirection: "column", gap: "var(--space-3)", padding: "var(--space-3)", border: "1px solid var(--color-border-default)", borderRadius: "var(--radius-sm)", background: "var(--color-bg-secondary)" }}>
                <div>
                  <label className="rd-label">目标节点 ID *</label>
                  <input className="rd-input" placeholder="输入 node_id" value={targetId} onChange={e => setTargetId(e.target.value)} required />
                </div>
                <div>
                  <label className="rd-label">关联类型 *</label>
                  <select className="rd-input" value={assocType} onChange={e => setAssocType(e.target.value as AssociationType)}>
                    <option value="RELATES_TO">RELATES_TO (相关)</option>
                    <option value="CONCEPTUAL_OVERLAP">CONCEPTUAL_OVERLAP (概念重叠)</option>
                  </select>
                </div>
                <div>
                  <label className="rd-label">关联原因</label>
                  <input className="rd-input" placeholder="为什么建立关联？" value={reason} onChange={e => setReason(e.target.value)} />
                </div>
                <div>
                  <label className="rd-label">置信度: {Math.round(confidence * 100)}%</label>
                  <input type="range" min="0" max="1" step="0.1" value={confidence} onChange={e => setConfidence(Number(e.target.value))} style={{ width: "100%" }} />
                </div>
                <div style={{ display: "flex", gap: "var(--space-2)", justifyContent: "flex-end" }}>
                  <button type="button" className="btn-secondary" onClick={() => setShowForm(false)}>取消</button>
                  <button type="submit" className="btn-primary" disabled={submitting || !targetId.trim()}>{submitting ? "提交中..." : "保存"}</button>
                </div>
              </form>
            )}
          </div>
        )}

        {associations.length === 0 ? (
          <EmptyState message="暂无语义关联" />
        ) : (
          <div style={{ display: "flex", flexDirection: "column", gap: "var(--space-3)" }}>
            {associations.map((assoc) => (
              <div
                key={assoc.association_id}
                style={{
                  border: "1px solid var(--color-border-default)",
                  borderRadius: "var(--radius-sm)",
                  padding: "var(--space-3)",
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
                  <span
                    style={{
                      display: "inline-flex",
                      padding: "1px var(--space-2)",
                      borderRadius: "var(--radius-full)",
                      background: "var(--color-bg-hover)",
                      fontSize: "var(--font-size-xs)",
                      color: "var(--color-text-secondary)",
                    }}
                  >
                    {assoc.type} &middot; {assoc.direction}
                  </span>
                  <span
                    style={{
                      fontSize: "var(--font-size-xs)",
                      color: "var(--color-text-tertiary)",
                    }}
                  >
                    {Math.round(assoc.confidence * 100)}%
                  </span>
                </div>
                <button
                  onClick={() => {
                    selectNode(assoc.related_node.node_id);
                    openDetailPanel(assoc.related_node.node_id);
                  }}
                  style={{
                    background: "none",
                    border: "none",
                    cursor: "pointer",
                    textAlign: "left",
                    padding: 0,
                    fontSize: "var(--font-size-sm)",
                    color: "var(--color-accent)",
                    fontFamily: "var(--font-ui)",
                  }}
                >
                  {assoc.related_node.content?.slice(0, 50) ??
                    assoc.related_node.summary_content?.slice(0, 50) ??
                    assoc.related_node.node_id.slice(0, 8)}
                </button>
                {assoc.reason && (
                  <div
                    style={{
                      fontSize: "var(--font-size-xs)",
                      color: "var(--color-text-tertiary)",
                      marginTop: "var(--space-1)",
                    }}
                  >
                    {assoc.reason}
                  </div>
                )}
                {isAdmin && (
                  <button
                    onClick={() => setDeleteTarget(assoc)}
                    style={{
                      marginTop: "var(--space-2)",
                      background: "none",
                      border: "none",
                      cursor: "pointer",
                      color: "var(--color-danger)",
                      fontSize: "var(--font-size-xs)",
                    }}
                  >
                    删除关联
                  </button>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
      <ConfirmDialog
        isOpen={deleteTarget !== null}
        title="确认删除关联"
        description={`将删除关联 ${deleteTarget?.association_id?.slice(0, 8)}...（${deleteTarget?.type}）`}
        isDestructive
        confirmText="确认删除"
        onConfirm={handleDelete}
        onCancel={() => setDeleteTarget(null)}
      />
    </>
  );
}
