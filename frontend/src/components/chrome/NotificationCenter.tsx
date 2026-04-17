import clsx from "clsx";
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
    <div className="absolute top-full right-0 mt-2 w-[320px] max-h-[400px] overflow-y-auto bg-bg-primary border border-border-default rounded-lg shadow-lg z-[200] font-ui text-sm">
      <div className="flex items-center justify-between px-4 py-3 border-b border-border-default">
        <span className="font-semibold text-text-primary">通知</span>
        <button
          type="button"
          onClick={markAllRead}
          className="border-none bg-transparent text-text-tertiary cursor-pointer font-ui text-xs px-1 py-[2px]"
        >
          全部已读
        </button>
      </div>

      {items.length === 0 ? (
        <div className="px-4 py-8 text-center text-text-tertiary">暂无通知</div>
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
              className={clsx(
                "flex items-start gap-3 px-4 py-3 cursor-pointer border-b border-border-default transition-[background] duration-150 hover:bg-bg-hover",
                item.read ? "bg-transparent" : "bg-bg-secondary",
              )}
            >
              <span
                className="shrink-0 w-2 h-2 rounded-full mt-[5px]"
                style={{ background: TYPE_COLOR[item.type] ?? "var(--color-text-tertiary)" }}
              />

              <div className="flex-1 min-w-0">
                <div className="text-text-primary leading-[1.4] break-words">
                  {item.message}
                </div>
                <div className="text-text-tertiary text-xs mt-[2px]">
                  {relativeTime(item.timestamp)}
                </div>
              </div>

              {!item.read && (
                <span className="shrink-0 w-[6px] h-[6px] rounded-full mt-[6px] bg-accent" />
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
