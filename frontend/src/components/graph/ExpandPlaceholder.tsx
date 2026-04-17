import { memo } from "react";
import { Handle, Position, type NodeProps } from "@xyflow/react";
import { useGraphStore } from "../../stores/graphStore";

const HIDDEN_HANDLE_STYLE = {
  opacity: 0,
  pointerEvents: "none",
} as const;

export const ExpandPlaceholder = memo(function ExpandPlaceholder({
  data,
}: NodeProps) {
  const parentNodeId = (data as { parentNodeId: string }).parentNodeId;
  const expanding = useGraphStore((s) => s.expandingNodeIds.has(parentNodeId));
  const expandChildren = useGraphStore((s) => s.expandChildren);

  const handleClick = () => {
    if (!expanding) {
      expandChildren(parentNodeId);
    }
  };

  return (
    <div
      className="flex flex-col items-center gap-1 cursor-pointer"
      onClick={handleClick}
      role="button"
      tabIndex={0}
      aria-label="Expand child nodes"
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          handleClick();
        }
      }}
    >
      <div className="expand-placeholder-circle w-12 h-12 rounded-full border-2 border-dashed border-border-default bg-bg-secondary flex items-center justify-center relative transition-[border-color] duration-[var(--transition-fast)]">
        {expanding ? (
          <div
            className="w-[18px] h-[18px] border-2 border-border-default rounded-full"
            style={{
              borderTopColor: "var(--color-text-secondary)",
              animation: "expand-spin 0.8s linear infinite",
            }}
          />
        ) : (
          <span className="text-[20px] leading-none text-text-tertiary select-none">
            +
          </span>
        )}
        <Handle
          id="source-center"
          type="source"
          position={Position.Top}
          style={{
            top: "50%",
            left: "50%",
            ...HIDDEN_HANDLE_STYLE,
          }}
        />
        <Handle
          id="target-center"
          type="target"
          position={Position.Top}
          style={{
            top: "50%",
            left: "50%",
            ...HIDDEN_HANDLE_STYLE,
          }}
        />
      </div>
      <span className="font-ui text-xs text-text-tertiary select-none">
        展开
      </span>
    </div>
  );
});
