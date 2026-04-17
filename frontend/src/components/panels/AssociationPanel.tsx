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
  const userId = useAuthStore((s) => s.userId);

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
    if (!targetId.trim() || !reason.trim() || !userId) return;

    setSubmitting(true);
    try {
      await createAssociation({
        source_node_id: nodeId,
        target_node_id: targetId.trim(),
        type: assocType,
        creator_id: userId,
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
      <div className="flex flex-col gap-3">
        <Skeleton variant="rectangular" height={56} />
        <Skeleton variant="rectangular" height={56} />
      </div>
    );
  }

  return (
    <>
      <div className="flex flex-col gap-3">
        {canCreate && (
          <div className="mb-1">
            {!showForm ? (
              <button className="btn-secondary w-full" onClick={() => setShowForm(true)}>
                + 添加语义关联
              </button>
            ) : (
              <form
                onSubmit={handleCreate}
                className="flex flex-col gap-3 p-3 border border-border-default rounded-sm bg-bg-secondary"
              >
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
                  <label className="rd-label">关联原因 *</label>
                  <input className="rd-input" placeholder="为什么建立关联？" value={reason} onChange={e => setReason(e.target.value)} required />
                </div>
                <div>
                  <label className="rd-label">置信度: {Math.round(confidence * 100)}%</label>
                  <input
                    type="range"
                    min="0"
                    max="1"
                    step="0.1"
                    value={confidence}
                    onChange={e => setConfidence(Number(e.target.value))}
                    className="w-full"
                  />
                </div>
                <div className="flex gap-2 justify-end">
                  <button type="button" className="btn-secondary" onClick={() => setShowForm(false)}>取消</button>
                  <button type="submit" className="btn-primary" disabled={submitting || !userId || !targetId.trim() || !reason.trim()}>
                    {submitting ? "提交中..." : "保存"}
                  </button>
                </div>
              </form>
            )}
          </div>
        )}

        {associations.length === 0 ? (
          <EmptyState message="暂无语义关联" />
        ) : (
          <div className="flex flex-col gap-3">
            {associations.map((assoc) => (
              <div
                key={assoc.association_id}
                className="border border-border-default rounded-sm p-3"
              >
                <div className="flex justify-between items-center mb-2">
                  <span className="inline-flex px-2 py-[1px] rounded-full bg-bg-hover text-xs text-text-secondary">
                    {assoc.type} &middot; {assoc.direction}
                  </span>
                  <span className="text-xs text-text-tertiary">
                    {Math.round(assoc.confidence * 100)}%
                  </span>
                </div>
                <button
                  onClick={() => {
                    selectNode(assoc.related_node.node_id);
                    openDetailPanel(assoc.related_node.node_id);
                  }}
                  className="bg-transparent border-none cursor-pointer text-left p-0 text-sm text-accent font-ui"
                >
                  {assoc.related_node.content?.slice(0, 50) ??
                    assoc.related_node.summary_content?.slice(0, 50) ??
                    assoc.related_node.node_id.slice(0, 8)}
                </button>
                {assoc.reason && (
                  <div className="text-xs text-text-tertiary mt-1">
                    {assoc.reason}
                  </div>
                )}
                {isAdmin && (
                  <button
                    onClick={() => setDeleteTarget(assoc)}
                    className="mt-2 bg-transparent border-none cursor-pointer text-danger text-xs"
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
