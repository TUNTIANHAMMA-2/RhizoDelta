import { useUiStore } from "../../stores/uiStore";

export function RhizoneList() {
  const toggleSidebar = useUiStore((s) => s.toggleLeftSidebar);

  return (
    <aside
      style={{
        width: 260,
        minWidth: 260,
        background: "var(--color-bg-secondary)",
        borderRight: "1px solid var(--color-border-default)",
        display: "flex",
        flexDirection: "column",
        fontFamily: "var(--font-ui)",
      }}
    >
      <div
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          padding: "var(--space-4)",
          borderBottom: "1px solid var(--color-border-default)",
        }}
      >
        <span style={{ fontWeight: 600, fontSize: "var(--font-size-sm)" }}>
          Rhizones
        </span>
        <button
          onClick={toggleSidebar}
          style={{
            background: "none",
            border: "none",
            cursor: "pointer",
            color: "var(--color-text-secondary)",
          }}
        >
          &laquo;
        </button>
      </div>
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
        暂无 Rhizone
      </div>
    </aside>
  );
}
