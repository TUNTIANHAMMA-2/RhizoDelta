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
      className="rd-panel fixed inset-x-0 bottom-0 z-[90] h-[85vh] max-h-[85vh] rounded-t-[28px] border border-border-default border-b-0 bg-bg-primary shadow-2xl flex flex-col font-ui md:relative md:inset-auto md:z-auto md:h-full md:max-h-none md:w-[50vw] md:min-w-[520px] md:max-w-[920px] md:rounded-none md:border-y-0 md:border-r-0 md:border-l md:shadow-none overflow-y-auto"
      onWheel={(e) => e.stopPropagation()}
      onTouchMove={(e) => e.stopPropagation()}
    >
      <div className="mx-auto mt-3 mb-1 h-1.5 w-12 rounded-pill bg-border-default/80 md:hidden" aria-hidden />
      <div className="rd-marker-edit hidden md:block" />
      <div className="flex justify-between items-center mb-4 px-4 md:px-0 md:pl-6 pt-2 md:pt-4">
        <span className="font-semibold text-md">{title}</span>
        <button
          onClick={closePanel}
          aria-label="关闭面板"
          className="bg-transparent border-none cursor-pointer text-text-secondary p-2 -mr-2 hover:bg-bg-hover rounded-md transition-colors"
        >
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <line x1="18" y1="6" x2="6" y2="18" />
            <line x1="6" y1="6" x2="18" y2="18" />
          </svg>
        </button>
      </div>

      <div className="flex-1 overflow-y-auto px-4 md:px-6 pb-4">
        {payload.formType === "post" && <PostForm />}
        {payload.formType === "inject" && (
          <InjectForm sourceNodeId={payload.nodeId} onSuccess={closePanel} />
        )}
        {payload.formType === "fork" && (
          <ForkForm sourceNodeId={payload.nodeId} onSuccess={closePanel} />
        )}
      </div>
    </aside>
  );
}
