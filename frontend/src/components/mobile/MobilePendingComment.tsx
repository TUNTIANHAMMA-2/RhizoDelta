import type { PendingPost } from "../../stores/discussionTreeStore";
import { mobileDiscussionTreeLabels as labels } from "../../i18n/labels";

export function MobilePendingComment({ pending }: { pending: PendingPost }) {
  const statusLabel =
    pending.status === "failed"
      ? labels.failed
      : pending.status === "accepted"
        ? labels.accepted
        : labels.sending;

  return (
    <div className="ml-3 mt-2 border-l-2 border-border-default pl-3">
      <div className="rounded-md border border-border-subtle bg-bg-secondary px-3 py-2 opacity-85">
        <div className="mb-1 flex items-center gap-2 font-ui text-xs text-text-secondary">
          {pending.status !== "failed" && (
            <span className="spinner h-3 w-3 border-[1.5px]" aria-hidden />
          )}
          <span>{statusLabel}</span>
          <span className="text-text-tertiary">{labels.pendingHint}</span>
        </div>
        <p className="whitespace-pre-wrap font-content text-sm leading-[1.55] text-text-primary">
          {pending.content}
        </p>
        {pending.status === "failed" && pending.errorMessage && (
          <p className="mt-1 font-ui text-xs text-danger">{pending.errorMessage}</p>
        )}
      </div>
    </div>
  );
}
