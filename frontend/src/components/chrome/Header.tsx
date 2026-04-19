import { useState, useCallback, useRef, useEffect } from "react";
import { useSseStore } from "../../stores/sseStore";
import { useAuthStore } from "../../stores/authStore";
import { useUiStore } from "../../stores/uiStore";
import { useGraphStore } from "../../stores/graphStore";
import { useNotificationStore } from "../../stores/notificationStore";
import { useNavigate, useLocation } from "react-router-dom";
import clsx from "clsx";
import { Breadcrumb } from "./Breadcrumb";
import { RoleBadge } from "./RoleBadge";
import { NotificationCenter } from "./NotificationCenter";

const SSE_STATUS_COLOR = {
  connecting: "var(--color-warning)",
  connected: "var(--color-success)",
  disconnected: "var(--color-danger)",
} as const;

const SSE_ANIM_CLASS = {
  connecting: "sse-indicator--connecting",
  connected: "",
  disconnected: "sse-indicator--disconnected",
} as const;

// Shared capsule surface classes (liquid-glass pill)
const CAPSULE_SURFACE =
  "bg-[rgba(253,252,249,0.88)] backdrop-blur-md backdrop-saturate-150 border border-border-default shadow-sm";

export function Header({ hideLogo = false }: { hideLogo?: boolean } = {}) {
  const navigate = useNavigate();
  const location = useLocation();
  const sseStatus = useSseStore((s) => s.status);
  const roles = useAuthStore((s) => s.roles);
  const userId = useAuthStore((s) => s.userId);
  const username = useAuthStore((s) => s.username);
  const clearToken = useAuthStore((s) => s.clearToken);
  const leftSidebarOpen = useUiStore((s) => s.leftSidebarOpen);
  const rightPanelMode = useUiStore((s) => s.rightPanelMode);
  const openReviewPanel = useUiStore((s) => s.openReviewPanel);
  const selectedNodeId = useGraphStore((s) => s.selectedNodeId);
  const [notifOpen, setNotifOpen] = useState(false);
  const unreadCount = useNotificationStore((s) => s.unreadCount);
  const [userHovered, setUserHovered] = useState(false);
  const hoverTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const notifContainerRef = useRef<HTMLDivElement>(null);

  const onWorkspace = location.pathname.startsWith("/workspace");

  useEffect(() => {
    if (!notifOpen) return;
    const onMouseDown = (e: MouseEvent) => {
      if (notifContainerRef.current && !notifContainerRef.current.contains(e.target as Node)) {
        setNotifOpen(false);
      }
    };
    document.addEventListener("mousedown", onMouseDown);
    return () => document.removeEventListener("mousedown", onMouseDown);
  }, [notifOpen]);

  const topRole = roles.includes("ADMIN")
    ? "ADMIN"
    : roles.includes("AGENT")
      ? "AGENT"
      : "USER";

  const handleLogout = useCallback(() => {
    clearToken();
    navigate("/login", { replace: true });
  }, [clearToken, navigate]);

  return (
    <header className="fixed top-0 left-0 z-[100] flex items-start justify-between w-full p-4 pointer-events-none font-ui text-sm box-border">
      <div className="flex items-center relative w-full h-10">
        {/* Logo 胶囊 */}
        {!hideLogo && (
          <div
            className={clsx(
              CAPSULE_SURFACE,
              "flex items-center h-10 rounded-lg px-4 shadow-md pointer-events-auto w-max box-border z-[2]",
              onWorkspace && "cursor-pointer group",
            )}
            onClick={onWorkspace ? () => navigate("/") : undefined}
            role={onWorkspace ? "link" : undefined}
            aria-label={onWorkspace ? "Back to home" : undefined}
          >
            {onWorkspace && (
              <span
                className="mr-2 text-text-tertiary group-hover:text-accent transition-colors"
                aria-hidden
              >
                ←
              </span>
            )}
            <div className="shrink-0 flex items-center font-content font-normal text-text-primary text-md tracking-[-0.02em]">
              RhizoDelt
              <span
                className={`${SSE_ANIM_CLASS[sseStatus]} text-lg ml-[2px]`}
                aria-live="polite"
                style={{ color: SSE_STATUS_COLOR[sseStatus] }}
              >
                △
              </span>
            </div>
          </div>
        )}

        {/* 面包屑胶囊 */}
        <div
          className={clsx(
            "absolute top-0 flex items-center h-10 rounded-lg overflow-hidden transition-[all] duration-300 ease-[var(--ease-out)] z-[1]",
            "bg-[rgba(253,252,249,0.88)] backdrop-blur-md backdrop-saturate-150",
            selectedNodeId
              ? "border border-border-default px-4 shadow-md pointer-events-auto opacity-100 max-w-[400px]"
              : "border border-transparent p-0 shadow-none pointer-events-none opacity-0 max-w-0",
          )}
          style={{
            left: selectedNodeId ? (leftSidebarOpen ? 260 : 156) : 80,
          }}
        >
          {selectedNodeId && <Breadcrumb />}
        </div>
      </div>

      {/* 右侧控件组 */}
      <div
        className="pointer-events-auto flex items-center gap-2 transition-transform duration-300 ease-[var(--ease-out)]"
        style={{
          transform:
            rightPanelMode !== "hidden"
              ? "translateX(calc(-1 * max(45vw, 460px)))"
              : "translateX(0)",
        }}
      >
        {topRole === "ADMIN" && (
          <button
            type="button"
            onClick={openReviewPanel}
            className={clsx(
              CAPSULE_SURFACE,
              "flex items-center h-9 rounded-full px-[14px] cursor-pointer font-ui text-xs font-semibold text-text-secondary transition-[all] duration-[var(--transition-fast)] tracking-[0.01em]",
            )}
          >
            复核
          </button>
        )}

        {/* 用户名胶囊 — hover 变退出按钮 */}
        <button
          type="button"
          onMouseEnter={() => {
            hoverTimerRef.current = setTimeout(() => setUserHovered(true), 300);
          }}
          onMouseLeave={() => {
            if (hoverTimerRef.current) {
              clearTimeout(hoverTimerRef.current);
              hoverTimerRef.current = null;
            }
            setUserHovered(false);
          }}
          onClick={userHovered ? handleLogout : undefined}
          className={clsx(
            "flex items-center gap-2 h-9 rounded-full px-[14px] backdrop-blur-md backdrop-saturate-150 shadow-sm font-ui text-xs font-medium transition-[all] duration-[var(--transition-fast)] min-w-0 whitespace-nowrap",
            userHovered
              ? "border border-danger bg-[rgba(196,69,58,0.06)] text-danger cursor-pointer"
              : "border border-border-default bg-[rgba(253,252,249,0.88)] text-text-secondary cursor-default",
          )}
        >
          {userHovered ? (
            <>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
                <polyline points="16 17 21 12 16 7" />
                <line x1="21" y1="12" x2="9" y2="12" />
              </svg>
              退出
            </>
          ) : (
            <>
              <RoleBadge role={topRole} />
              {username ?? userId ?? "anonymous"}
            </>
          )}
        </button>

        {/* 通知铃铛 */}
        <div ref={notifContainerRef} className="relative">
          <button
            type="button"
            onClick={() => setNotifOpen(!notifOpen)}
            aria-label="通知"
            className={clsx(
              CAPSULE_SURFACE,
              "relative flex items-center justify-center w-9 h-9 rounded-full cursor-pointer p-0 transition-[all] duration-[var(--transition-fast)]",
            )}
          >
            <svg
              width="16"
              height="16"
              viewBox="0 0 24 24"
              fill="none"
              stroke="var(--color-text-secondary)"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" />
              <path d="M13.73 21a2 2 0 0 1-3.46 0" />
            </svg>
            {unreadCount > 0 && (
              <span className="absolute -top-[2px] -right-[2px] min-w-4 h-4 rounded-full bg-danger text-white text-[10px] font-bold flex items-center justify-center px-1 leading-none">
                {unreadCount > 99 ? "99+" : unreadCount}
              </span>
            )}
          </button>
          <NotificationCenter isOpen={notifOpen} onClose={() => setNotifOpen(false)} />
        </div>
      </div>
    </header>
  );
}
