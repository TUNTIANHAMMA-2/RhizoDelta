import { useEffect, useState } from "react";
import { useGraphStore } from "../../stores/graphStore";
import { useAuthStore } from "../../stores/authStore";
import { useUiStore } from "../../stores/uiStore";
import { deleteAssociation } from "../../api/associations";
import { Skeleton } from "../feedback/Skeleton";
import { EmptyState } from "../feedback/EmptyState";
import { ConfirmDialog } from "../modals/ConfirmDialog";
import type { AssociationInfo } from "../../api/types";

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
  const [loading, setLoading] = useState(true);
  const [deleteTarget, setDeleteTarget] = useState<AssociationInfo | null>(null);

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

  if (loading) {
    return (
      <div style={{ display: "flex", flexDirection: "column", gap: "var(--space-3)" }}>
        <Skeleton variant="rectangular" height={56} />
        <Skeleton variant="rectangular" height={56} />
      </div>
    );
  }

  if (associations.length === 0) {
    return <EmptyState message="暂无语义关联" />;
  }

  return (
    <>
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
