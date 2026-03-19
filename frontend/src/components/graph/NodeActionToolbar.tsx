import { useUiStore } from "../../stores/uiStore";

interface Props {
  nodeId: string;
}

export function NodeActionToolbar({ nodeId }: Props) {
  const openEditPanel = useUiStore((s) => s.openEditPanel);

  return (
    <div
      style={{
        display: "flex",
        gap: "var(--space-2)",
        padding: "var(--space-2) var(--space-3)",
        background: "var(--color-bg-primary)",
        borderRadius: "var(--radius-md)",
        boxShadow: "var(--shadow-md)",
        fontFamily: "var(--font-ui)",
        fontSize: "var(--font-size-sm)",
      }}
    >
      <button
        onClick={() => openEditPanel(nodeId, "inject")}
        style={{ cursor: "pointer", background: "none", border: "none", padding: "var(--space-1) var(--space-2)" }}
      >
        延续注入
      </button>
      <button
        onClick={() => openEditPanel(nodeId, "fork")}
        style={{ cursor: "pointer", background: "none", border: "none", padding: "var(--space-1) var(--space-2)" }}
      >
        分叉
      </button>
    </div>
  );
}
