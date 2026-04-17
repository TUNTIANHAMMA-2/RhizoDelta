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
      className="rd-panel w-[45vw] min-w-[460px] relative overflow-y-auto p-4 border-l border-border-default bg-bg-primary"
      onWheel={(e) => e.stopPropagation()}
      onTouchMove={(e) => e.stopPropagation()}
    >
      <div className="rd-marker-edit" />
      <div className="flex justify-between items-center mb-4 pl-6">
        <span className="font-semibold text-md">{title}</span>
        <button
          onClick={closePanel}
          aria-label="关闭面板"
          className="bg-transparent border-none cursor-pointer text-md text-text-secondary"
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
