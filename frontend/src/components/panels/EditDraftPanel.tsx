import { useUiStore } from "../../stores/uiStore";
import { InjectForm } from "../forms/InjectForm";
import { ForkForm } from "../forms/ForkForm";

export function EditDraftPanel() {
  const payload = useUiStore((s) => s.rightPanelPayload);
  const closePanel = useUiStore((s) => s.closeRightPanel);

  if (!payload) return null;

  return (
    <aside
      style={{
        width: 360,
        minWidth: 360,
        borderLeft: "1px solid var(--color-border-default)",
        background: "var(--color-bg-primary)",
        overflowY: "auto",
        fontFamily: "var(--font-ui)",
        padding: "var(--space-4)",
      }}
    >
      <div
        style={{
          display: "flex",
          justifyContent: "space-between",
          marginBottom: "var(--space-4)",
        }}
      >
        <span style={{ fontWeight: 600, fontSize: "var(--font-size-md)" }}>
          {payload.formType === "inject" ? "延续注入" : "分叉"}
        </span>
        <button
          onClick={closePanel}
          style={{
            background: "none",
            border: "none",
            cursor: "pointer",
            fontSize: "var(--font-size-md)",
            color: "var(--color-text-secondary)",
          }}
        >
          &times;
        </button>
      </div>

      {payload.formType === "inject" && (
        <InjectForm sourceNodeId={payload.nodeId} onSuccess={closePanel} />
      )}
      {payload.formType === "fork" && (
        <ForkForm sourceNodeId={payload.nodeId} onSuccess={closePanel} />
      )}
    </aside>
  );
}
