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
    <span className="text-text-tertiary mx-1 shrink-0">/</span>
  );

  return (
    <nav className="flex items-center font-ui text-sm overflow-hidden whitespace-nowrap w-full">
      {rootNodeId && rootNodeId !== selectedNodeId && (
        <>
          <button
            onClick={() => handleClick(rootNodeId)}
            className="bg-transparent border-none cursor-pointer text-text-secondary font-ui text-sm p-0 shrink-0"
          >
            Root
          </button>
          {separator}
          <span className="text-text-tertiary shrink-0">...</span>
          {separator}
        </>
      )}
      <span className="text-text-primary font-medium overflow-hidden text-ellipsis">
        {selectedNode?.label.replace("_", " ") ?? selectedNodeId.slice(0, 8)}
      </span>
    </nav>
  );
}
