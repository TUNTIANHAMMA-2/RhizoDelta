import { useUiStore } from "../../stores/uiStore";
import { InjectForm } from "../forms/InjectForm";
import { ForkForm } from "../forms/ForkForm";
import { PostForm } from "../forms/PostForm";

const TITLE_MAP: Record<string, string> = {
  inject: "延续注入",
  fork: "分叉",
  post: "发布观点",
};

export function EditDraftPanel() {
  const payload = useUiStore((s) => s.rightPanelPayload);
  const closePanel = useUiStore((s) => s.closeRightPanel);

  if (!payload) return null;

  const title = TITLE_MAP[payload.formType ?? ""] ?? "编辑";

  return (
    <aside
      className="rd-panel"
      onWheel={(e) => e.stopPropagation()}
      onTouchMove={(e) => e.stopPropagation()}
      style={{
        width: 360,
        minWidth: 360,
        overflowY: "auto",
        padding: "var(--space-4)",
      }}
    >
      <div
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          marginBottom: "var(--space-4)",
        }}
      >
        <span style={{ fontWeight: 600, fontSize: "var(--font-size-md)" }}>
          {title}
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

      {payload.formType === "post" && <PostForm />}
      {payload.formType === "inject" && (
        <InjectForm sourceNodeId={payload.nodeId} onSuccess={closePanel} />
      )}
      {payload.formType === "fork" && (
        <ForkForm sourceNodeId={payload.nodeId} onSuccess={closePanel} />
      )}
    </aside>
  );
}
