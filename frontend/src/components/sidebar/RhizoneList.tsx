import { useUiStore } from "../../stores/uiStore";

export function RhizoneList() {
  const toggleSidebar = useUiStore((s) => s.toggleLeftSidebar);
  const openPostPanel = useUiStore((s) => s.openPostPanel);

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
        paddingTop: 40,
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

      {/* 发布观点入口 */}
      <div
        style={{
          padding: "var(--space-3) var(--space-4)",
          borderTop: "1px solid var(--color-border-default)",
        }}
      >
        <button
          onClick={openPostPanel}
          style={{
            width: "100%",
            padding: "var(--space-2) var(--space-4)",
            background: "var(--color-accent)",
            color: "#fff",
            border: "none",
            borderRadius: "var(--radius-sm)",
            fontFamily: "var(--font-ui)",
            fontSize: "var(--font-size-sm)",
            fontWeight: 500,
            cursor: "pointer",
          }}
        >
          + 发布观点
        </button>
      </div>
    </aside>
  );
}
