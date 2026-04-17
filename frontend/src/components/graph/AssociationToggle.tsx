import { useCallback } from "react";
import { Panel } from "@xyflow/react";
import clsx from "clsx";
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
    <Panel position="top-left" style={{ marginTop: 8, marginLeft: 8 }}>
      <button
        type="button"
        onClick={handleClick}
        className={clsx(
          "border border-border-default rounded-sm px-4 py-2 cursor-pointer font-ui text-sm shadow-sm backdrop-blur-md transition-[all] duration-[var(--transition-fast)]",
          showAssociations
            ? "bg-text-primary text-bg-primary font-semibold"
            : "bg-[rgba(255,255,255,0.88)] text-text-secondary font-medium",
        )}
      >
        关联
      </button>
    </Panel>
  );
}
