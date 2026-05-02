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
      className="rd-panel w-[38vw] min-w-[420px] max-w-[720px] relative border-l border-border-default bg-bg-primary flex flex-col font-ui h-full"
      onWheel={(e) => e.stopPropagation()}
      onTouchMove={(e) => e.stopPropagation()}
    >
      <div className="p-4 border-b border-border-default flex justify-between items-center">
        <span className="font-semibold text-md">人工复核</span>
        <button
          onClick={closePanel}
          aria-label="关闭面板"
          className="bg-transparent border-none cursor-pointer text-md text-text-secondary"
        >
          &times;
        </button>
      </div>

      <div className="flex-1 overflow-y-auto p-4">
        {loading && items.length === 0 ? (
          <div className="flex flex-col gap-3">
            <Skeleton variant="rectangular" height={60} />
            <Skeleton variant="rectangular" height={60} />
            <Skeleton variant="rectangular" height={60} />
          </div>
        ) : loadError ? (
          <EmptyState message="加载复核任务失败，请稍后重试" />
        ) : items.length === 0 ? (
          <EmptyState message="暂无待复核任务" />
        ) : (
          <div className="flex flex-col gap-0">
            {items.map((review) => {
              const expanded = expandedId === review.review_id;
              const statusColor = STATUS_COLOR[review.status] ?? "var(--color-text-tertiary)";
              return (
                <div
                  key={review.review_id}
                  className="border-l-2 border-border-default pl-4 pb-4 relative"
                >
                  <span
                    className="absolute -left-[5px] top-[2px] w-2 h-2 rounded-full"
                    style={{ background: statusColor }}
                  />

                  <button
                    onClick={() => setExpandedId(expanded ? null : review.review_id)}
                    className="bg-transparent border-none cursor-pointer text-left p-0 w-full"
                  >
                    <div className="flex items-center gap-2 mb-1">
                      <span
                        className="inline-flex px-2 py-[1px] rounded-full text-xs font-medium"
                        style={{
                          background: (STATUS_COLOR[review.status] ?? "#ccc") + "1a",
                          color: STATUS_COLOR[review.status] ?? "var(--color-text-secondary)",
                        }}
                      >
                        {review.status}
                      </span>
                      <span className="text-xs text-text-tertiary">
                        {ACTION_LABEL[review.suggested_action] ?? review.suggested_action}
                      </span>
                    </div>
                    {review.review_reason_codes.length > 0 && (
                      <div className="flex flex-wrap gap-1 mb-1">
                        {review.review_reason_codes.map((code) => (
                          <span
                            key={code}
                            className="text-xs px-1 rounded-sm bg-bg-hover text-text-secondary"
                          >
                            {code}
                          </span>
                        ))}
                      </div>
                    )}
                    <div className="text-xs text-text-tertiary">
                      {new Date(review.created_at).toLocaleString()}
                    </div>
                  </button>

                  {expanded && (
                    <div className="mt-2 p-3 bg-bg-secondary rounded-sm text-xs">
                      <div className="mb-1">
                        <strong>review_id:</strong> {review.review_id}
                      </div>
                      <div className="mb-1">
                        <strong>post_node_id:</strong> {review.post_node_id}
                      </div>
                      <div className="mb-1">
                        <strong>candidate_node_ids:</strong>{" "}
                        {review.candidate_node_ids.length > 0
                          ? review.candidate_node_ids.join(", ")
                          : "—"}
                      </div>
                      {review.draft_payload && Object.keys(review.draft_payload).length > 0 && (
                        <div className="mb-1">
                          <strong>draft_payload:</strong>
                          <pre className="mt-1 p-2 bg-bg-primary rounded-sm overflow-auto max-h-[120px] text-xs">
                            {JSON.stringify(review.draft_payload, null, 2)}
                          </pre>
                        </div>
                      )}
                      <div className="mb-1">
                        <strong>expires_at:</strong>{" "}
                        {new Date(review.expires_at).toLocaleString()}
                      </div>

                      {isAdmin && (
                        <div className="flex gap-2 mt-3">
                          {review.suggested_action === "MERGE" && (
                            <button
                              onClick={(e) => {
                                e.stopPropagation();
                                setConfirmAction({ type: "approve-merge", reviewId: review.review_id });
                              }}
                              className="btn-primary text-xs!"
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
                              className="btn-primary text-xs!"
                            >
                              批准分支
                            </button>
                          )}
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              setConfirmAction({ type: "reject", reviewId: review.review_id });
                            }}
                            className="bg-transparent border-none cursor-pointer text-danger text-xs font-medium"
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
