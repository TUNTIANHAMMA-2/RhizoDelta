import { useEffect, useRef, useState } from "react";
import { createPost } from "../../api/posts";
import { mobileDiscussionTreeLabels as labels } from "../../i18n/labels";
import { useAuthStore } from "../../stores/authStore";
import { useDiscussionTreeStore } from "../../stores/discussionTreeStore";
import { previewText } from "./mobileUtils";

export function MobileReplyComposer() {
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const [content, setContent] = useState("");
  const userId = useAuthStore((s) => s.userId);
  const rootId = useDiscussionTreeStore((s) => s.rootId);
  const selectedReplyTargetId = useDiscussionTreeStore((s) => s.selectedReplyTargetId);
  const targetNode = useDiscussionTreeStore((s) =>
    s.nodesById.get(s.selectedReplyTargetId ?? s.rootId ?? ""),
  );
  const pendingCount = useDiscussionTreeStore((s) => s.pendingPosts.size);
  const selectReplyTarget = useDiscussionTreeStore((s) => s.selectReplyTarget);
  const addPendingPost = useDiscussionTreeStore((s) => s.addPendingPost);
  const failPendingPost = useDiscussionTreeStore((s) => s.failPendingPost);
  const isReplyToRoot = Boolean(rootId && selectedReplyTargetId === rootId);
  const disabled = content.trim().length === 0 || !userId || !rootId || pendingCount >= 5;

  useEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea) return;
    textarea.style.height = "0px";
    textarea.style.height = `${Math.min(textarea.scrollHeight, 100)}px`;
  }, [content]);

  const onSubmit = async () => {
    if (disabled || !userId || !rootId) return;
    const targetNodeId = selectedReplyTargetId ?? rootId;
    const requestId = crypto.randomUUID();
    const trimmed = content.trim();
    addPendingPost({
      requestId,
      targetNodeId,
      content: trimmed,
      authorId: userId,
      createdAt: new Date().toISOString(),
      status: "submitting",
    });
    setContent("");
    try {
      await createPost({
        request_id: requestId,
        author_id: userId,
        content: trimmed,
        target_node_id: targetNodeId,
      });
    } catch (error) {
      failPendingPost(
        requestId,
        error instanceof Error ? error.message : labels.failed,
      );
    }
  };

  return (
    <div
      className="fixed inset-x-0 bottom-0 z-[110] border-t border-border-subtle bg-bg-canvas/75 px-3 pt-3 shadow-[0_-8px_24px_-12px_rgba(26,29,27,0.12)] backdrop-blur-xl backdrop-saturate-150 supports-[backdrop-filter]:bg-bg-canvas/55 animate-sheet-up"
      style={{ paddingBottom: "calc(env(safe-area-inset-bottom) + 8px)" }}
    >
      <div className="mb-2 flex items-center justify-between gap-2 font-ui text-xs text-text-secondary">
        <div aria-live="polite" className="min-w-0 flex-1 truncate">
          {isReplyToRoot
            ? labels.replyToRoot
            : labels.replyToTarget(previewText(targetNode?.content, 36))}
        </div>
        {!isReplyToRoot && (
          <button
            type="button"
            onClick={() => selectReplyTarget(null)}
            className="rounded-sm border border-border-default bg-bg-secondary px-2 py-1 text-xs text-text-secondary transition-colors duration-150 hover:bg-bg-hover"
          >
            {labels.cancelReply}
          </button>
        )}
      </div>
      <div className="flex items-end gap-2">
        <textarea
          ref={textareaRef}
          value={content}
          onChange={(event) => setContent(event.target.value)}
          rows={1}
          aria-label={labels.replyToRoot}
          className="max-h-[100px] min-h-10 flex-1 resize-none rounded-md border border-border-default bg-bg-elevated px-3 py-2 font-content text-base leading-[1.45] text-text-primary outline-none transition-shadow duration-150 focus:border-border-focus focus:shadow-[inset_0_1px_2px_rgba(26,29,27,0.04)] focus:ring-2 focus:ring-accent/20"
        />
        <button
          type="button"
          onClick={onSubmit}
          disabled={disabled}
          className="h-10 rounded-md border border-accent-deep bg-accent-deep px-4 font-ui text-sm font-semibold text-bg-primary transition-all duration-200 active:scale-[0.96] disabled:opacity-50 disabled:active:scale-100"
        >
          {labels.sendButton}
        </button>
      </div>
    </div>
  );
}
