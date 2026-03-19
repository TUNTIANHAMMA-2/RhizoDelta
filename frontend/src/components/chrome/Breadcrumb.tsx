import { useGraphStore } from "../../stores/graphStore";
import { useUiStore } from "../../stores/uiStore";

export function Breadcrumb() {
  const selectedNodeId = useGraphStore((s) => s.selectedNodeId);
  const rootNodeId = useGraphStore((s) => s.rootNodeId);
  const nodes = useGraphStore((s) => s.nodes);
  const selectNode = useGraphStore((s) => s.selectNode);
  const openDetailPanel = useUiStore((s) => s.openDetailPanel);

  if (!selectedNodeId) return null;

  const selectedNode = nodes.get(selectedNodeId);

  const handleClick = (nodeId: string) => {
    selectNode(nodeId);
    openDetailPanel(nodeId);
  };

  const separator = (
    <span
      style={{
        color: "var(--color-text-tertiary)",
        margin: "0 var(--space-1)",
      }}
    >
      /
    </span>
  );

  return (
    <nav
      style={{
        display: "flex",
        alignItems: "center",
        fontFamily: "var(--font-ui)",
        fontSize: "var(--font-size-sm)",
      }}
    >
      {rootNodeId && rootNodeId !== selectedNodeId && (
        <>
          <button
            onClick={() => handleClick(rootNodeId)}
            style={{
              background: "none",
              border: "none",
              cursor: "pointer",
              color: "var(--color-text-secondary)",
              fontFamily: "var(--font-ui)",
              fontSize: "var(--font-size-sm)",
              padding: 0,
            }}
          >
            Root
          </button>
          {separator}
          <span style={{ color: "var(--color-text-tertiary)" }}>...</span>
          {separator}
        </>
      )}
      <span
        style={{
          color: "var(--color-text-primary)",
          fontWeight: 500,
        }}
      >
        {selectedNode?.label.replace("_", " ") ?? selectedNodeId.slice(0, 8)}
      </span>
    </nav>
  );
}
