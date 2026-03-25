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
      <button
        onClick={(e) => {
          e.stopPropagation();
          openEditPanel(nodeId, "inject");
        }}
        className="btn-primary"
        style={{ display: canEdit ? undefined : "none" }}
      >
        延续注入
      </button>
      <button
        onClick={(e) => {
          e.stopPropagation();
          openEditPanel(nodeId, "fork");
        }}
        className="btn-secondary"
        style={{ display: canEdit ? undefined : "none" }}
      >
        分叉
      </button>
    </div>
  );
}
