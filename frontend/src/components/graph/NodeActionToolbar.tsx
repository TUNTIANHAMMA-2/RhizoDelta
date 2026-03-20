import { useUiStore } from "../../stores/uiStore";

interface Props {
  nodeId: string;
}

export function NodeActionToolbar({ nodeId }: Props) {
  const openEditPanel = useUiStore((s) => s.openEditPanel);

  return (
    <div
      style={{
        position: "relative",
        display: "flex",
        gap: "var(--space-2)",
        padding: "var(--space-2) var(--space-3)",
        background: "var(--color-bg-primary)",
        borderRadius: "var(--radius-md)",
        boxShadow: "var(--shadow-md)",
        fontFamily: "var(--font-ui)",
        border: "1px solid var(--color-border-default)",
        fontSize: "var(--font-size-sm)",
      }}
    >
      <div
        style={{
          position: "absolute",
          top: -6,
          left: "50%",
          transform: "translateX(-50%) rotate(45deg)",
          width: 10,
          height: 10,
          background: "var(--color-bg-primary)",
          borderLeft: "1px solid var(--color-border-default)",
          borderTop: "1px solid var(--color-border-default)",
        }}
      />
      <button
        onClick={(e) => {
          e.stopPropagation();
          openEditPanel(nodeId, "inject");
        }}
        className="btn-primary"
      >
        延续注入
      </button>
      <button
        onClick={(e) => {
          e.stopPropagation();
          openEditPanel(nodeId, "fork");
        }}
        className="btn-secondary"
      >
        分叉
      </button>
    </div>
  );
}
