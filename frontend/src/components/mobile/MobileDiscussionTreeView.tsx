import { useEffect } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { Header } from "../chrome/Header";
import { Skeleton } from "../feedback/Skeleton";
import { ToastContainer } from "../feedback/Toast";
import { useSse } from "../../hooks/useSse";
import { mobileDiscussionTreeLabels as labels } from "../../i18n/labels";
import { useDiscussionTreeStore } from "../../stores/discussionTreeStore";
import { useGraphStore } from "../../stores/graphStore";
import { CommentTreeItem } from "./CommentTreeItem";
import { LongPressMenu } from "./LongPressMenu";
import { MobileReplyComposer } from "./MobileReplyComposer";

const SKELETON_ROWS: Array<{ indent: string; lines: string[] }> = [
  { indent: "ml-0", lines: ["100%", "92%", "68%"] },
  { indent: "ml-3", lines: ["96%", "74%"] },
  { indent: "ml-6", lines: ["88%", "60%"] },
  { indent: "ml-3", lines: ["100%", "82%", "54%"] },
  { indent: "ml-6", lines: ["72%"] },
];

export function MobileDiscussionTreeView() {
  const { rhizomeId } = useParams<{ rhizomeId?: string }>();
  const navigate = useNavigate();
  const rootId = useDiscussionTreeStore((s) => s.rootId);
  const meta = useDiscussionTreeStore((s) => s.meta);
  const loadingState = useDiscussionTreeStore((s) => s.loadingState);
  const error = useDiscussionTreeStore((s) => s.error);
  const loadTree = useDiscussionTreeStore((s) => s.loadTree);
  const refreshTree = useDiscussionTreeStore((s) => s.refreshTree);
  const loadRhizomes = useGraphStore((s) => s.loadRhizomes);

  useSse();

  useEffect(() => {
    let cancelled = false;
    const resolveRoot = async () => {
      if (rhizomeId) return rhizomeId;
      await loadRhizomes();
      return useGraphStore.getState().rhizomes[0]?.node_id ?? null;
    };

    resolveRoot()
      .then((nextRootId) => {
        if (cancelled) return;
        if (!nextRootId) {
          navigate("/", { replace: true });
          return;
        }
        return loadTree(nextRootId);
      })
      .catch(() => {
        if (!cancelled) navigate("/", { replace: true });
      });

    return () => {
      cancelled = true;
    };
  }, [rhizomeId, loadRhizomes, loadTree, navigate]);

  useEffect(() => {
    const onVisibilityChange = () => {
      if (document.visibilityState === "visible") {
        refreshTree();
      }
    };
    document.addEventListener("visibilitychange", onVisibilityChange);
    return () => document.removeEventListener("visibilitychange", onVisibilityChange);
  }, [refreshTree]);

  return (
    <div className="min-h-screen bg-bg-canvas pb-[148px] font-ui">
      <Header hideLogo />
      <button
        type="button"
        onClick={() => navigate("/")}
        className="fixed left-3 top-3 z-[120] h-10 rounded-md border border-border-default bg-bg-primary/90 px-3 text-sm text-text-secondary shadow-sm backdrop-blur-md"
      >
        ← 主页
      </button>

      <main className="mx-auto max-w-[760px] px-3 pt-16">
        {loadingState === "loading" && (
          <div className="flex flex-col gap-5" aria-label="Loading discussion tree" role="status">
            {SKELETON_ROWS.map((row, rowIndex) => (
              <div
                key={rowIndex}
                className={`flex gap-3 ${row.indent} animate-fade-in`}
                style={{ animationDelay: `${rowIndex * 80}ms`, animationFillMode: "backwards" }}
              >
                <div className="flex flex-col items-center gap-1 pt-1">
                  <Skeleton variant="circular" width={28} height={28} />
                  <div className="w-[2px] flex-1 rounded-full bg-border-subtle/60" aria-hidden />
                </div>
                <div className="flex-1 space-y-2 pt-1">
                  <div className="flex items-center gap-2">
                    <Skeleton variant="text" width={80} height={12} />
                    <Skeleton variant="text" width={36} height={10} className="opacity-60" />
                  </div>
                  <div className="space-y-1.5 pt-1">
                    {row.lines.map((width, lineIndex) => (
                      <Skeleton key={lineIndex} variant="text" width={width} height={11} />
                    ))}
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}

        {loadingState === "error" && (
          <div className="rounded-md border border-border-default bg-bg-primary p-4 text-center font-ui text-sm text-text-secondary">
            <p>{labels.loadError}</p>
            {error && <p className="mt-1 text-xs text-text-tertiary">{error}</p>}
            <button
              type="button"
              onClick={() => rootId && loadTree(rootId)}
              className="mt-3 rounded-md border border-border-default bg-bg-secondary px-3 py-2 text-sm text-text-primary"
            >
              Retry
            </button>
          </div>
        )}

        {loadingState === "loaded" && rootId && <CommentTreeItem nodeId={rootId} depth={0} />}

        {loadingState === "loaded" && meta?.truncated && (
          <p className="mt-3 rounded-md bg-bg-secondary px-3 py-2 font-ui text-xs text-text-tertiary">
            {labels.truncatedHint}
          </p>
        )}
      </main>

      <MobileReplyComposer />
      <LongPressMenu />
      <ToastContainer />
    </div>
  );
}
