import { useCallback, useEffect, useState, type AnimationEvent } from "react";
import { useNavigate } from "react-router-dom";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import clsx from "clsx";
import { mobileDiscussionTreeLabels as labels } from "../../i18n/labels";
import { useViewport } from "../../hooks/useViewport";
import { useDiscussionTreeStore } from "../../stores/discussionTreeStore";
import { useUiStore } from "../../stores/uiStore";

export function LongPressMenu() {
  const navigate = useNavigate();
  const { isMobile } = useViewport();
  const [showDetails, setShowDetails] = useState(false);
  const [closing, setClosing] = useState(false);
  const [focusRestoreId, setFocusRestoreId] = useState<string | null>(null);
  const nodeId = useDiscussionTreeStore((s) => s.longPressMenuNodeId);
  const node = useDiscussionTreeStore((s) =>
    s.longPressMenuNodeId ? s.nodesById.get(s.longPressMenuNodeId) : null,
  );
  const rootId = useDiscussionTreeStore((s) => s.rootId);
  const closeLongPressMenu = useDiscussionTreeStore((s) => s.closeLongPressMenu);
  const addToast = useUiStore((s) => s.addToast);

  // React-canonical "adjust state when a prop changes" pattern. When the store
  // hands us a fresh nodeId, the sheet should reset to its opening state so the
  // user always sees a clean entrance animation.
  const [prevNodeId, setPrevNodeId] = useState<string | null>(nodeId);
  if (nodeId !== prevNodeId) {
    setPrevNodeId(nodeId);
    if (nodeId) {
      setFocusRestoreId(nodeId);
      if (closing) setClosing(false);
    }
  }

  const dismiss = useCallback(() => {
    if (!nodeId) return;
    setClosing(true);
  }, [nodeId]);

  const onSheetAnimationEnd = useCallback(
    (event: AnimationEvent<HTMLDivElement>) => {
      if (event.target !== event.currentTarget) return;
      if (!closing) return;
      const restoreId = focusRestoreId;
      setShowDetails(false);
      setClosing(false);
      closeLongPressMenu();
      requestAnimationFrame(() => {
        if (!restoreId) return;
        document
          .querySelector<HTMLElement>(`[data-comment-node-id="${restoreId}"]`)
          ?.focus();
      });
    },
    [closeLongPressMenu, closing, focusRestoreId],
  );

  useEffect(() => {
    if (!nodeId) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") dismiss();
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [dismiss, nodeId]);

  if (!nodeId || !node) return null;

  const onCopy = async () => {
    await navigator.clipboard.writeText(node.content ?? "");
    addToast({ type: "success", message: "已复制" });
    dismiss();
  };

  const onViewInGraph = () => {
    if (!rootId) return;
    if (isMobile) {
      addToast({ type: "info", message: "请在桌面端打开图谱位置" });
      return;
    }
    navigate(`/workspace/${rootId}?focus=${node.node_id}`);
    dismiss();
  };

  return (
    <div
      className={clsx(
        "fixed inset-0 z-[180] bg-[rgba(26,29,27,0.28)] backdrop-blur-[2px] transition-opacity duration-200",
        closing ? "opacity-0" : "animate-fade-in opacity-100",
      )}
      onClick={dismiss}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label={labels.longPressMenuAria}
        className={clsx(
          "absolute inset-x-0 bottom-0 rounded-t-md border border-border-default bg-bg-primary px-4 pb-4 pt-3",
          "shadow-[0_-12px_32px_-12px_rgba(26,29,27,0.18)]",
          closing ? "animate-sheet-down" : "animate-sheet-up",
        )}
        onClick={(event) => event.stopPropagation()}
        onAnimationEnd={onSheetAnimationEnd}
      >
        <div
          className="mx-auto mb-3 h-1 w-10 rounded-full bg-border-default opacity-60"
          aria-hidden
        />
        {showDetails ? (
          <div className="max-h-[70vh] overflow-y-auto">
            <div className="mb-3 font-ui text-xs text-text-tertiary">
              <span>{node.author.display_name ?? node.author.username ?? node.author.user_id}</span>
              <span className="mx-2">·</span>
              <span>{node.node_id}</span>
            </div>
            <div className="font-content text-base leading-[1.6] text-text-primary">
              <ReactMarkdown remarkPlugins={[remarkGfm]}>{node.content ?? ""}</ReactMarkdown>
            </div>
          </div>
        ) : (
          <div className="flex flex-col gap-2 font-ui text-sm text-text-primary">
            <button
              type="button"
              onClick={onCopy}
              className="rounded-md bg-bg-secondary/80 px-3 py-3 text-left transition-colors duration-150 hover:bg-bg-hover active:bg-bg-hover"
            >
              {labels.longPressCopy}
            </button>
            <button
              type="button"
              onClick={() => setShowDetails(true)}
              className="rounded-md bg-bg-secondary/80 px-3 py-3 text-left transition-colors duration-150 hover:bg-bg-hover active:bg-bg-hover"
            >
              {labels.longPressDetails}
            </button>
            <button
              type="button"
              onClick={onViewInGraph}
              className="rounded-md bg-bg-secondary/80 px-3 py-3 text-left transition-colors duration-150 hover:bg-bg-hover active:bg-bg-hover"
            >
              {labels.longPressViewInGraph}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
