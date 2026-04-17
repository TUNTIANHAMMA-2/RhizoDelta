import { useCallback } from "react";
import { Panel } from "@xyflow/react";
import { useGraphStore } from "../../stores/graphStore";

export function AssociationToggle() {
  const showAssociations = useGraphStore((s) => s.showAssociations);
  const toggleAssociations = useGraphStore((s) => s.toggleAssociations);
  const loadAssociations = useGraphStore((s) => s.loadAssociations);
  const rootNodeId = useGraphStore((s) => s.rootNodeId);

  const handleClick = useCallback(() => {
    toggleAssociations();
    if (!showAssociations && rootNodeId) {
      loadAssociations(rootNodeId)
        .then(() => useGraphStore.getState().flushLayout())
        .catch(console.error);
    }
  }, [showAssociations, rootNodeId, loadAssociations, toggleAssociations]);

  return (
    <Panel position="top-left" style={{ marginTop: 60, marginLeft: 500 }}>
      <button
        type="button"
        onClick={handleClick}
        style={{
          border: "1px solid var(--color-border-default)",
          borderRadius: "var(--radius-sm)",
          padding: "var(--space-2) var(--space-4)",
          background: showAssociations
            ? "var(--color-text-primary)"
            : "rgba(255, 255, 255, 0.88)",
          color: showAssociations
            ? "var(--color-bg-primary)"
            : "var(--color-text-secondary)",
          fontWeight: showAssociations ? 600 : 500,
          cursor: "pointer",
          fontFamily: "var(--font-ui)",
          fontSize: "var(--font-size-sm)",
          transition: "all var(--transition-fast)",
          boxShadow: "var(--shadow-sm)",
          backdropFilter: "blur(12px)",
          WebkitBackdropFilter: "blur(12px)",
        }}
      >
        关联
      </button>
    </Panel>
  );
}
