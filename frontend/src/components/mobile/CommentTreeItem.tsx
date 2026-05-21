import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import clsx from "clsx";
import { useMemo } from "react";
import { mobileDiscussionTreeLabels as labels } from "../../i18n/labels";
import { useLongPress } from "../../hooks/useLongPress";
import { useDiscussionTreeStore } from "../../stores/discussionTreeStore";
import { ClosureNote } from "./ClosureNote";
import { MobilePendingComment } from "./MobilePendingComment";
import { formatRelativeTime } from "./mobileUtils";
import type { DiscussionArtifact } from "../../api/types";

const INDENT_CLASS = ["ml-0", "ml-3", "ml-6", "ml-9"];
const EMPTY_CHILD_IDS: string[] = [];
const EMPTY_ARTIFACTS: DiscussionArtifact[] = [];

export function CommentTreeItem({ nodeId, depth }: { nodeId: string; depth: number }) {
  const node = useDiscussionTreeStore((s) => s.nodesById.get(nodeId));
  const childrenByParent = useDiscussionTreeStore((s) => s.childrenByParent);
  const artifactsByAnchor = useDiscussionTreeStore((s) => s.artifactsByAnchor);
  const pendingPostsMap = useDiscussionTreeStore((s) => s.pendingPosts);
  const selectedReplyTargetId = useDiscussionTreeStore((s) => s.selectedReplyTargetId);
  const activeArtifactId = useDiscussionTreeStore((s) => s.activeArtifactId);
  const activeArtifact = useMemo(() => {
    if (!activeArtifactId) return null;
    for (const artifactList of artifactsByAnchor.values()) {
      const match = artifactList.find((artifact) => artifact.node_id === activeArtifactId);
      if (match) return match;
    }
    return null;
  }, [activeArtifactId, artifactsByAnchor]);
  const childIds = childrenByParent.get(nodeId) ?? EMPTY_CHILD_IDS;
  const artifacts = artifactsByAnchor.get(nodeId) ?? EMPTY_ARTIFACTS;
  const pendingPosts = useMemo(
    () => Array.from(pendingPostsMap.values()).filter((post) => post.targetNodeId === nodeId),
    [nodeId, pendingPostsMap],
  );
  const selectReplyTarget = useDiscussionTreeStore((s) => s.selectReplyTarget);
  const openLongPressMenu = useDiscussionTreeStore((s) => s.openLongPressMenu);
  const longPressHandlers = useLongPress(() => openLongPressMenu(nodeId));

  if (!node) return null;

  const isSelected = selectedReplyTargetId === nodeId;
  const isSourceHighlighted = Boolean(
    activeArtifactId && activeArtifact?.source_node_ids.includes(nodeId),
  );
  const hiddenChildrenCount = Math.max(0, node.total_children_count - childIds.length);

  return (
    <article
      className={clsx(
        "relative pl-4 rounded-md border border-transparent py-3 pr-3 transition-colors animate-fade-in animate-slide-up [animation-duration:300ms]",
        INDENT_CLASS[Math.min(depth, 3)],
        isSelected && "bg-bg-selected/80 border-border-default",
      )}
      onClick={(event) => {
        event.stopPropagation();
        selectReplyTarget(nodeId);
      }}
      {...longPressHandlers}
      style={{
        WebkitUserSelect: "none",
        WebkitTouchCallout: "none",
      }}
      tabIndex={0}
      data-comment-node-id={nodeId}
      data-source-highlighted={isSourceHighlighted || undefined}
    >
      <header className="mb-2 flex items-center gap-2 font-ui text-xs text-text-tertiary">
        <span className="font-semibold text-text-secondary">
          {node.author.display_name ?? node.author.username ?? node.author.user_id ?? "anonymous"}
        </span>
        <time dateTime={node.created_at ?? undefined}>{formatRelativeTime(node.created_at)}</time>
        {depth >= 3 && (
          <span className="rounded-[4px] bg-bg-secondary/40 px-[6px] py-[2px] text-[10px] font-medium text-text-tertiary border border-border-subtle/40">
            {labels.depthBadge(depth)}
          </span>
        )}
      </header>

      <div className="font-content text-base leading-[1.62] text-text-primary">
        <ReactMarkdown remarkPlugins={[remarkGfm]}>{node.content ?? ""}</ReactMarkdown>
      </div>

      <div>
        {artifacts.map((artifact) => (
          <ClosureNote key={`${artifact.node_id}-${artifact.anchor_node_id}`} artifact={artifact} />
        ))}
      </div>

      <div className="mt-2 flex flex-col gap-1">
        {childIds.map((childId) => (
          <CommentTreeItem key={childId} nodeId={childId} depth={depth + 1} />
        ))}
        {pendingPosts.map((pending) => (
          <MobilePendingComment key={pending.requestId} pending={pending} />
        ))}
      </div>

      {node.has_more_children && hiddenChildrenCount > 0 && (
        <p className="mt-2 font-ui text-xs text-text-tertiary">
          {labels.hasMoreChildren(hiddenChildrenCount)}
        </p>
      )}
    </article>
  );
}
