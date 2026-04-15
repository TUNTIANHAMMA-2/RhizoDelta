import { useEffect, useState } from "react";
import { fetchPendingReviews, approveMerge, approveBranch, rejectReview } from "../../api/reviews";
import { useAuthStore } from "../../stores/authStore";
import { useUiStore } from "../../stores/uiStore";
import { Skeleton } from "../feedback/Skeleton";
import { EmptyState } from "../feedback/EmptyState";
import { ConfirmDialog } from "../modals/ConfirmDialog";
import type { ReviewTaskPayload } from "../../api/types";

const STATUS_COLOR: Record<string, string> = {
  PENDING: "var(--color-warning)",
  APPROVED: "var(--color-success)",
  REJECTED: "var(--color-danger)",
};

const ACTION_LABEL: Record<string, string> = {
  MERGE: "合并",
  BRANCH: "分支",
};

type ConfirmAction = {
  type: "approve-merge" | "approve-branch" | "reject";
  reviewId: string;
};

export function ReviewPanel() {
  const [items, setItems] = useState<ReviewTaskPayload[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [confirmAction, setConfirmAction] = useState<ConfirmAction | null>(null);
  const isAdmin = useAuthStore((s) => s.hasRole("ADMIN"));
  const closePanel = useUiStore((s) => s.closeRightPanel);
  const addToast = useUiStore((s) => s.addToast);

  const load = () => {
    setLoading(true);
    setLoadError(false);
    fetchPendingReviews(50)
      .then((res) => setItems(res ?? []))
      .catch(() => {
        setItems([]);
        setLoadError(true);
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
  }, []);

  const handleConfirm = async () => {
    if (!confirmAction) return;
    try {
      if (confirmAction.type === "approve-merge") {
        await approveMerge(confirmAction.reviewId);
        addToast({ type: "success", message: "已批准合并" });
      } else if (confirmAction.type === "approve-branch") {
        await approveBranch(confirmAction.reviewId);
        addToast({ type: "success", message: "已批准分支" });
      } else {
        await rejectReview(confirmAction.reviewId);
        addToast({ type: "success", message: "已拒绝复核" });
      }
      setConfirmAction(null);
      load();
    } catch {
      addToast({ type: "error", message: "操作失败" });
    }
  };

  const confirmTitle =
    confirmAction?.type === "reject" ? "确认拒绝" : "确认批准";
  const confirmDesc =
    confirmAction?.type === "approve-merge"
      ? "确认批准合并此复核任务？操作后将执行合并流程。"
      : confirmAction?.type === "approve-branch"
        ? "确认批准分支此复核任务？操作后将创建新分支。"
        : "确认拒绝此复核任务？";

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
      {/* Header */}
      <div
        style={{
          padding: "var(--space-4)",
          borderBottom: "1px solid var(--color-border-default)",
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
        }}
      >
        <span style={{ fontWeight: 600, fontSize: "var(--font-size-md)" }}>
          人工复核
        </span>
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

      {/* Content */}
      <div style={{ flex: 1, overflowY: "auto", padding: "var(--space-4)" }}>
        {loading && items.length === 0 ? (
          <div style={{ display: "flex", flexDirection: "column", gap: "var(--space-3)" }}>
            <Skeleton variant="rectangular" height={60} />
            <Skeleton variant="rectangular" height={60} />
            <Skeleton variant="rectangular" height={60} />
          </div>
        ) : loadError ? (
          <EmptyState message="加载复核任务失败，请稍后重试" />
        ) : items.length === 0 ? (
          <EmptyState message="暂无待复核任务" />
        ) : (
          <div style={{ display: "flex", flexDirection: "column", gap: 0 }}>
            {items.map((review) => {
              const expanded = expandedId === review.review_id;
              return (
                <div
                  key={review.review_id}
                  style={{
                    borderLeft: "2px solid var(--color-border-default)",
                    paddingLeft: "var(--space-4)",
                    paddingBottom: "var(--space-4)",
                    position: "relative",
                  }}
                >
                  {/* Timeline dot */}
                  <span
                    style={{
                      position: "absolute",
                      left: -5,
                      top: 2,
                      width: 8,
                      height: 8,
                      borderRadius: "var(--radius-full)",
                      background: STATUS_COLOR[review.status] ?? "var(--color-text-tertiary)",
                    }}
                  />

                  <button
                    onClick={() => setExpandedId(expanded ? null : review.review_id)}
                    style={{
                      background: "none",
                      border: "none",
                      cursor: "pointer",
                      textAlign: "left",
                      padding: 0,
                      width: "100%",
                    }}
                  >
                    <div
                      style={{
                        display: "flex",
                        alignItems: "center",
                        gap: "var(--space-2)",
                        marginBottom: "var(--space-1)",
                      }}
                    >
                      {/* Status badge */}
                      <span
                        style={{
                          display: "inline-flex",
                          padding: "1px var(--space-2)",
                          borderRadius: "var(--radius-full)",
                          background: (STATUS_COLOR[review.status] ?? "#ccc") + "1a",
                          color: STATUS_COLOR[review.status] ?? "var(--color-text-secondary)",
                          fontSize: "var(--font-size-xs)",
                          fontWeight: 500,
                        }}
                      >
                        {review.status}
                      </span>
                      {/* Suggested action */}
                      <span
                        style={{
                          fontSize: "var(--font-size-xs)",
                          color: "var(--color-text-tertiary)",
                        }}
                      >
                        {ACTION_LABEL[review.suggested_action] ?? review.suggested_action}
                      </span>
                    </div>
                    {/* Reason codes */}
                    {review.review_reason_codes.length > 0 && (
                      <div
                        style={{
                          display: "flex",
                          flexWrap: "wrap",
                          gap: "var(--space-1)",
                          marginBottom: "var(--space-1)",
                        }}
                      >
                        {review.review_reason_codes.map((code) => (
                          <span
                            key={code}
                            style={{
                              fontSize: "var(--font-size-xs)",
                              padding: "0 var(--space-1)",
                              borderRadius: "var(--radius-sm)",
                              background: "var(--color-bg-tertiary)",
                              color: "var(--color-text-secondary)",
                            }}
                          >
                            {code}
                          </span>
                        ))}
                      </div>
                    )}
                    <div
                      style={{
                        fontSize: "var(--font-size-xs)",
                        color: "var(--color-text-tertiary)",
                      }}
                    >
                      {new Date(review.created_at).toLocaleString()}
                    </div>
                  </button>

                  {expanded && (
                    <div
                      style={{
                        marginTop: "var(--space-2)",
                        padding: "var(--space-3)",
                        background: "var(--color-bg-secondary)",
                        borderRadius: "var(--radius-sm)",
                        fontSize: "var(--font-size-xs)",
                      }}
                    >
                      <div style={{ marginBottom: "var(--space-1)" }}>
                        <strong>review_id:</strong> {review.review_id}
                      </div>
                      <div style={{ marginBottom: "var(--space-1)" }}>
                        <strong>post_node_id:</strong> {review.post_node_id}
                      </div>
                      <div style={{ marginBottom: "var(--space-1)" }}>
                        <strong>candidate_node_ids:</strong>{" "}
                        {review.candidate_node_ids.length > 0
                          ? review.candidate_node_ids.join(", ")
                          : "—"}
                      </div>
                      {review.draft_payload && Object.keys(review.draft_payload).length > 0 && (
                        <div style={{ marginBottom: "var(--space-1)" }}>
                          <strong>draft_payload:</strong>
                          <pre
                            style={{
                              margin: "var(--space-1) 0 0",
                              padding: "var(--space-2)",
                              background: "var(--color-bg-primary)",
                              borderRadius: "var(--radius-sm)",
                              overflow: "auto",
                              maxHeight: 120,
                              fontSize: "var(--font-size-xs)",
                            }}
                          >
                            {JSON.stringify(review.draft_payload, null, 2)}
                          </pre>
                        </div>
                      )}
                      <div style={{ marginBottom: "var(--space-1)" }}>
                        <strong>expires_at:</strong>{" "}
                        {new Date(review.expires_at).toLocaleString()}
                      </div>

                      {isAdmin && (
                        <div
                          style={{
                            display: "flex",
                            gap: "var(--space-2)",
                            marginTop: "var(--space-3)",
                          }}
                        >
                          {review.suggested_action === "MERGE" && (
                            <button
                              onClick={(e) => {
                                e.stopPropagation();
                                setConfirmAction({ type: "approve-merge", reviewId: review.review_id });
                              }}
                              className="btn-primary"
                              style={{ fontSize: "var(--font-size-xs)" }}
                            >
                              批准合并
                            </button>
                          )}
                          {review.suggested_action === "BRANCH" && (
                            <button
                              onClick={(e) => {
                                e.stopPropagation();
                                setConfirmAction({ type: "approve-branch", reviewId: review.review_id });
                              }}
                              className="btn-primary"
                              style={{ fontSize: "var(--font-size-xs)" }}
                            >
                              批准分支
                            </button>
                          )}
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              setConfirmAction({ type: "reject", reviewId: review.review_id });
                            }}
                            style={{
                              background: "none",
                              border: "none",
                              cursor: "pointer",
                              color: "var(--color-danger)",
                              fontSize: "var(--font-size-xs)",
                              fontWeight: 500,
                            }}
                          >
                            拒绝
                          </button>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>

      <ConfirmDialog
        isOpen={confirmAction !== null}
        title={confirmTitle}
        description={confirmDesc}
        isDestructive={confirmAction?.type === "reject"}
        confirmText={confirmAction?.type === "reject" ? "确认拒绝" : "确认批准"}
        onConfirm={handleConfirm}
        onCancel={() => setConfirmAction(null)}
      />
    </aside>
  );
}
