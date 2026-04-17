import { useUiStore } from "../../stores/uiStore";
import { useGraphStore } from "../../stores/graphStore";
import type { GraphNodeDTO } from "../../api/types";
import { loadGraphForRoot } from "../../lib/loadGraphForRoot";
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
    <aside
      style={{
        position: "relative",
        width: 260,
        minWidth: 260,
        height: "100%",
        background: "var(--color-bg-secondary)",
        borderRight: "1px solid var(--color-border-default)",
        display: "flex",
        flexDirection: "column",
        fontFamily: "var(--font-ui)",
        paddingTop: 80,
        boxSizing: "border-box",
      }}
    >
      <div
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          padding: "var(--space-4)",
          borderBottom: "1px solid var(--color-border-subtle)",
        }}
      >
        <span style={{ fontWeight: 600, fontSize: "var(--font-size-sm)", letterSpacing: "0.02em", color: "var(--color-text-secondary)" }}>
          Rhizones
        </span>
      </div>

      <button
        className="sidebar-toggle-btn"
        onClick={toggleSidebar}
        aria-label="折叠侧边栏"
        style={{
          position: "absolute",
          top: "50%",
          right: -14,
          transform: "translateY(-50%)",
          zIndex: 10,
          width: 28,
          height: 28,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          background: "var(--color-bg-elevated)",
          border: "1px solid var(--color-border-default)",
          borderRadius: "var(--radius-full)",
          cursor: "pointer",
          color: "var(--color-text-tertiary)",
          boxShadow: "var(--shadow-sm)",
          padding: 0,
          transition: "all var(--transition-fast)",
        }}
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <polyline points="15 18 9 12 15 6"></polyline>
        </svg>
      </button>

      <div
        style={{
          flex: 1,
          overflowY: "auto",
          display: "flex",
          flexDirection: "column",
        }}
      >
        {rhizomes.length === 0 ? (
          <div
            style={{
              flex: 1,
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              color: "var(--color-text-tertiary)",
              fontSize: "var(--font-size-sm)",
            }}
          >
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
                  style={{
                    fontSize: "var(--font-size-sm)",
                    color: "var(--color-text-primary)",
                    fontWeight: isSelected ? 600 : 500,
                    whiteSpace: "nowrap",
                    overflow: "hidden",
                    textOverflow: "ellipsis",
                    width: "100%",
                  }}
                >
                  {plainText || "Unknown Topic"}
                </div>
                <div
                  style={{
                    fontSize: 11,
                    color: "var(--color-text-secondary)",
                    display: "flex",
                    justifyContent: "space-between",
                    width: "100%",
                  }}
                >
                  <span>{new Date(node.created_at).toLocaleDateString()}</span>
                  <span>{node.author_id ?? "Anonymous"}</span>
                </div>
              </button>
            );
          })
        )}
      </div>

      {/* 发布观点入口 */}
      <div
        style={{
          padding: "var(--space-3) var(--space-4)",
          borderTop: "1px solid var(--color-border-subtle)",
        }}
      >
        <button
          className="btn-primary"
          onClick={() => {
            selectNode(null);
            openPostPanel();
          }}
          style={{ width: "100%", borderRadius: "var(--radius-md)" }}
        >
          + 发起新话题
        </button>
      </div>
    </aside>
  );
}
