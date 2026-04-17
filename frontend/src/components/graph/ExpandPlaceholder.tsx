import { memo } from "react";
import { Handle, Position, type NodeProps } from "@xyflow/react";
import { useGraphStore } from "../../stores/graphStore";

const HIDDEN_HANDLE_STYLE = {
  opacity: 0,
  pointerEvents: "none",
} as const;

const containerStyle: React.CSSProperties = {
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
  gap: 4,
  cursor: "pointer",
};

const circleBaseStyle: React.CSSProperties = {
  width: 48,
  height: 48,
  borderRadius: "var(--radius-full)",
  border: "2px dashed var(--color-border-default)",
  background: "var(--color-bg-secondary)",
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  transition: "border-color var(--transition-fast)",
  position: "relative",
};

const plusStyle: React.CSSProperties = {
  fontSize: 20,
  lineHeight: 1,
  color: "var(--color-text-tertiary)",
  userSelect: "none",
};

const labelStyle: React.CSSProperties = {
  fontFamily: "var(--font-ui)",
  fontSize: "var(--font-size-xs)",
  color: "var(--color-text-tertiary)",
  userSelect: "none",
};

const spinnerStyle: React.CSSProperties = {
  width: 18,
  height: 18,
  border: "2px solid var(--color-border-default)",
  borderTopColor: "var(--color-text-secondary)",
  borderRadius: "var(--radius-full)",
  animation: "expand-spin 0.8s linear infinite",
};

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
        style={containerStyle}
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
        <div
          className="expand-placeholder-circle"
          style={circleBaseStyle}
        >
          {expanding ? (
            <div style={spinnerStyle} />
          ) : (
            <span style={plusStyle}>+</span>
          )}
          {/* Center handles for edge connection */}
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
        <span style={labelStyle}>{"\u5C55\u5F00"}</span>
      </div>
  );
});
