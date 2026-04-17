import { useNotificationStore } from "../../stores/notificationStore";
import { useGraphStore } from "../../stores/graphStore";
import { useUiStore } from "../../stores/uiStore";
import type { NotificationItem } from "../../stores/notificationStore";

const TYPE_COLOR: Record<NotificationItem["type"], string> = {
  node_created: "var(--color-node-human)",
  edge_created: "var(--color-text-tertiary)",
  decision_complete: "var(--color-node-consensus)",
  orchestration_status: "var(--color-warning)",
  summary_generated: "var(--color-node-result)",
  quality_scored: "var(--color-success)",
};

function relativeTime(timestamp: string): string {
  const diff = Date.now() - new Date(timestamp).getTime();
  const seconds = Math.floor(diff / 1000);
  if (seconds < 60) return "刚刚";
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}分钟前`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}小时前`;
  const days = Math.floor(hours / 24);
  return `${days}天前`;
}

interface NotificationCenterProps {
  isOpen: boolean;
  onClose: () => void;
}

export function NotificationCenter({ isOpen, onClose }: NotificationCenterProps) {
  const items = useNotificationStore((s) => s.items);
  const markRead = useNotificationStore((s) => s.markRead);
  const markAllRead = useNotificationStore((s) => s.markAllRead);

  if (!isOpen) return null;

  const handleItemClick = (item: NotificationItem) => {
    if (item.nodeId) {
      useGraphStore.getState().selectNode(item.nodeId);
      useUiStore.getState().openDetailPanel(item.nodeId);
    }
    markRead(item.id);
    onClose();
  };

  return (
    <div
      style={{
        position: "absolute",
        top: "100%",
        right: 0,
        marginTop: 8,
        width: 320,
        maxHeight: 400,
        overflowY: "auto",
        background: "var(--color-bg-primary)",
        border: "1px solid var(--color-border-default)",
        borderRadius: "var(--radius-lg)",
        boxShadow: "var(--shadow-lg)",
        zIndex: 200,
        fontFamily: "var(--font-ui)",
        fontSize: "var(--font-size-sm)",
      }}
    >
      {/* Header */}
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          padding: "var(--space-3) var(--space-4)",
          borderBottom: "1px solid var(--color-border-default)",
        }}
      >
        <span
          style={{
            fontWeight: 600,
            color: "var(--color-text-primary)",
          }}
        >
          通知
        </span>
        <button
          type="button"
          onClick={markAllRead}
          style={{
            border: "none",
            background: "none",
            color: "var(--color-text-tertiary)",
            cursor: "pointer",
            fontFamily: "var(--font-ui)",
            fontSize: "var(--font-size-xs)",
            padding: "2px 4px",
          }}
        >
          全部已读
        </button>
      </div>

      {/* List */}
      {items.length === 0 ? (
        <div
          style={{
            padding: "var(--space-8) var(--space-4)",
            textAlign: "center",
            color: "var(--color-text-tertiary)",
          }}
        >
          暂无通知
        </div>
      ) : (
        <div>
          {items.map((item) => (
            <div
              key={item.id}
              role="button"
              tabIndex={0}
              onClick={() => handleItemClick(item)}
              onKeyDown={(e) => {
                if (e.key === "Enter" || e.key === " ") {
                  e.preventDefault();
                  handleItemClick(item);
                }
              }}
              style={{
                display: "flex",
                alignItems: "flex-start",
                gap: "var(--space-3)",
                padding: "var(--space-3) var(--space-4)",
                cursor: "pointer",
                borderBottom: "1px solid var(--color-border-default)",
                background: item.read ? "transparent" : "var(--color-bg-secondary)",
                transition: "background 150ms ease",
              }}
              onMouseEnter={(e) => {
                (e.currentTarget as HTMLElement).style.background = "var(--color-bg-tertiary)";
              }}
              onMouseLeave={(e) => {
                (e.currentTarget as HTMLElement).style.background = item.read
                  ? "transparent"
                  : "var(--color-bg-secondary)";
              }}
            >
              {/* Color dot */}
              <span
                style={{
                  flexShrink: 0,
                  width: 8,
                  height: 8,
                  borderRadius: "50%",
                  marginTop: 5,
                  background: TYPE_COLOR[item.type] ?? "var(--color-text-tertiary)",
                }}
              />

              {/* Content */}
              <div style={{ flex: 1, minWidth: 0 }}>
                <div
                  style={{
                    color: "var(--color-text-primary)",
                    lineHeight: 1.4,
                    wordBreak: "break-word",
                  }}
                >
                  {item.message}
                </div>
                <div
                  style={{
                    color: "var(--color-text-tertiary)",
                    fontSize: "var(--font-size-xs)",
                    marginTop: 2,
                  }}
                >
                  {relativeTime(item.timestamp)}
                </div>
              </div>

              {/* Unread indicator */}
              {!item.read && (
                <span
                  style={{
                    flexShrink: 0,
                    width: 6,
                    height: 6,
                    borderRadius: "50%",
                    marginTop: 6,
                    background: "var(--color-accent)",
                  }}
                />
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
