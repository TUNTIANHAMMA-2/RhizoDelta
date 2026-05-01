import clsx from "clsx";
import { useUiStore } from "../../stores/uiStore";
import { useGraphStore } from "../../stores/graphStore";
import type { GraphNodeDTO } from "../../api/types";
import { AuthorLabel } from "../shared/AuthorLabel";
import { selectRhizome } from "../../lib/selectRhizome";
import { stripMarkdown } from "../../lib/markdown";

export function RhizoneList() {
  const toggleSidebar = useUiStore((s) => s.toggleLeftSidebar);
  const openPostPanel = useUiStore((s) => s.openPostPanel);
  const closeRightPanel = useUiStore((s) => s.closeRightPanel);

  const rhizomes = useGraphStore((s) => s.rhizomes);
  const rootNodeId = useGraphStore((s) => s.rootNodeId);
  const loadLineage = useGraphStore((s) => s.loadLineage);
  const loadChildren = useGraphStore((s) => s.loadChildren);
  const selectNode = useGraphStore((s) => s.selectNode);

  const handleRhizomeClick = (node: GraphNodeDTO) => {
    void selectRhizome(node.node_id, {
      selectNode,
      closeRightPanel,
      loadGraphForRoot: (nodeId) =>
        loadGraphForRoot(nodeId, {
          loadLineage,
          loadChildren,
          onChildrenError: console.error,
        }),
    });
  };

  return (
    <aside className="relative w-[260px] min-w-[260px] h-full bg-bg-secondary border-r border-border-default flex flex-col font-ui pt-20 box-border">
      <div className="flex justify-between items-center p-4 border-b border-border-subtle">
        <span className="font-semibold text-sm tracking-[0.02em] text-text-secondary">
          Rhizones
        </span>
      </div>

      <button
        className="sidebar-toggle-btn absolute top-1/2 -right-[14px] -translate-y-1/2 z-10 w-7 h-7 flex items-center justify-center bg-bg-elevated border border-border-default rounded-full cursor-pointer text-text-tertiary shadow-sm p-0 transition-[all] duration-[var(--transition-fast)]"
        onClick={toggleSidebar}
        aria-label="折叠侧边栏"
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <polyline points="15 18 9 12 15 6"></polyline>
        </svg>
      </button>

      <div className="flex-1 overflow-y-auto flex flex-col">
        {rhizomes.length === 0 ? (
          <div className="flex-1 flex items-center justify-center text-text-tertiary text-sm">
            暂无话题
          </div>
        ) : (
          rhizomes.map((node) => {
            const isSelected = node.node_id === rootNodeId;
            const plainText = stripMarkdown(node.content ?? node.summary_content);
            return (
              <button
                key={node.node_id}
                className="rd-list-item"
                onClick={() => handleRhizomeClick(node)}
                aria-current={isSelected ? "true" : undefined}
              >
                <div
                  className={clsx(
                    "text-sm text-text-primary whitespace-nowrap overflow-hidden text-ellipsis w-full",
                    isSelected ? "font-semibold" : "font-medium",
                  )}
                >
                  {plainText || "Unknown Topic"}
                </div>
                <div className="text-[11px] text-text-secondary flex justify-between w-full">
                  <span>{new Date(node.created_at).toLocaleDateString()}</span>
                  <AuthorLabel
                    displayName={node.author_display_name}
                    username={node.author_username}
                    authorId={node.author_id}
                  />
                </div>
              </button>
            );
          })
        )}
      </div>

      <div className="px-4 py-3 border-t border-border-subtle">
        <button
          className="btn-primary w-full rounded-md!"
          onClick={() => {
            selectNode(null);
            openPostPanel();
          }}
        >
          + 发起新话题
        </button>
      </div>
    </aside>
  );
}
