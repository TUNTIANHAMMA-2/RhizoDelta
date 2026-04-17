import { useUiStore } from "../../stores/uiStore";
import { useAuthStore } from "../../stores/authStore";
import { useGraphStore } from "../../stores/graphStore";

interface Props {
  nodeId: string;
}

export function NodeActionToolbar({ nodeId }: Props) {
  const openEditPanel = useUiStore((s) => s.openEditPanel);
  const openPostPanel = useUiStore((s) => s.openPostPanel);
  const roles = useAuthStore((s) => s.roles);
  const userId = useAuthStore((s) => s.userId);
  const selectNode = useGraphStore((s) => s.selectNode);
  const canReply = userId !== null;
  const canEdit = roles.includes("AGENT") || roles.includes("ADMIN");

  if (!canReply && !canEdit) {
    return null;
  }

  return (
    <div className="relative flex gap-2 px-3 py-2 bg-bg-primary rounded-md shadow-md font-ui border border-border-default text-sm">
      <div className="absolute -top-[6px] left-1/2 w-[10px] h-[10px] bg-bg-primary border-l border-t border-border-default -translate-x-1/2 rotate-45" />
      {canReply && (
        <button
          onClick={(e) => {
            e.stopPropagation();
            selectNode(nodeId);
            openPostPanel();
          }}
          className="btn-primary"
        >
          回复
        </button>
      )}
      {canEdit && (
        <>
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
        </>
      )}
    </div>
  );
}
