import { useState, useCallback, useRef, useEffect } from "react";
import { useSseStore } from "../../stores/sseStore";
import { useAuthStore } from "../../stores/authStore";
import { useUiStore } from "../../stores/uiStore";
import { useGraphStore } from "../../stores/graphStore";
import { useNotificationStore } from "../../stores/notificationStore";
import { useNavigate } from "react-router-dom";
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

export function Header() {
  const navigate = useNavigate();
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

  // Click-outside to close notification panel
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
    <header
      style={{
        position: "fixed",
        top: 0,
        left: 0,
        zIndex: 100,
        display: "flex",
        alignItems: "flex-start",
        justifyContent: "space-between",
        width: "100%",
        padding: "var(--space-4)",
        pointerEvents: "none",
        fontFamily: "var(--font-ui)",
        fontSize: "var(--font-size-sm)",
        boxSizing: "border-box",
      }}
    >
      <div style={{ display: "flex", alignItems: "center", position: "relative", width: "100%", height: 40 }}>
        {/* Logo 胶囊 */}
        <div
          style={{
            display: "flex",
            alignItems: "center",
            height: 40,
            background: "rgba(253, 252, 249, 0.88)",
            backdropFilter: "blur(16px) saturate(1.3)",
            WebkitBackdropFilter: "blur(16px) saturate(1.3)",
            border: "1px solid var(--color-border-default)",
            borderRadius: "var(--radius-lg)",
            padding: "0 var(--space-4)",
            boxShadow: "var(--shadow-md)",
            pointerEvents: "auto",
            width: "max-content",
            boxSizing: "border-box",
            zIndex: 2,
          }}
        >
          <div
            style={{
              flexShrink: 0,
              display: "flex",
              alignItems: "center",
              fontFamily: "var(--font-content)",
              fontWeight: 400,
              color: "var(--color-text-primary)",
              fontSize: "var(--font-size-md)",
              letterSpacing: "-0.02em",
            }}
          >
            RhizoDelt
            <span
              className={SSE_ANIM_CLASS[sseStatus]}
              aria-live="polite"
              style={{
                color: SSE_STATUS_COLOR[sseStatus],
                fontSize: "var(--font-size-lg)",
                marginLeft: 2,
              }}
            >
              △
            </span>
          </div>
        </div>

        {/* 面包屑胶囊 */}
        <div
          style={{
            position: "absolute",
            top: 0,
            left: selectedNodeId
              ? (leftSidebarOpen ? 260 : 156)
              : 80,
            display: "flex",
            alignItems: "center",
            height: 40,
            background: "rgba(253, 252, 249, 0.88)",
            backdropFilter: "blur(16px) saturate(1.3)",
            WebkitBackdropFilter: "blur(16px) saturate(1.3)",
            border: selectedNodeId ? "1px solid var(--color-border-default)" : "1px solid transparent",
            borderRadius: "var(--radius-lg)",
            padding: selectedNodeId ? "0 var(--space-4)" : "0",
            boxShadow: selectedNodeId ? "var(--shadow-md)" : "none",
            pointerEvents: selectedNodeId ? "auto" : "none",
            opacity: selectedNodeId ? 1 : 0,
            maxWidth: selectedNodeId ? 400 : 0,
            overflow: "hidden",
            transition: "all 300ms var(--ease-out)",
            zIndex: 1,
          }}
        >
          {selectedNodeId && <Breadcrumb />}
        </div>
      </div>

      {/* 右侧控件组 */}
      <div
        style={{
          pointerEvents: "auto",
          display: "flex",
          alignItems: "center",
          gap: "var(--space-2)",
          transition: "transform 300ms var(--ease-out)",
          transform: rightPanelMode !== "hidden" ? "translateX(calc(-1 * max(45vw, 460px)))" : "translateX(0)",
        }}
      >
        {/* ADMIN 复核按钮 */}
        {topRole === "ADMIN" && (
          <button
            type="button"
            onClick={openReviewPanel}
            style={{
              display: "flex",
              alignItems: "center",
              height: 36,
              border: "1px solid var(--color-border-default)",
              borderRadius: "var(--radius-full)",
              padding: "0 14px",
              background: "rgba(253, 252, 249, 0.88)",
              backdropFilter: "blur(16px) saturate(1.3)",
              WebkitBackdropFilter: "blur(16px) saturate(1.3)",
              boxShadow: "var(--shadow-sm)",
              cursor: "pointer",
              fontFamily: "var(--font-ui)",
              fontSize: "var(--font-size-xs)",
              fontWeight: 600,
              color: "var(--color-text-secondary)",
              transition: "all var(--transition-fast)",
              letterSpacing: "0.01em",
            }}
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
          style={{
            display: "flex",
            alignItems: "center",
            gap: "var(--space-2)",
            height: 36,
            border: userHovered
              ? "1px solid var(--color-danger)"
              : "1px solid var(--color-border-default)",
            borderRadius: "var(--radius-full)",
            padding: "0 14px",
            background: userHovered
              ? "rgba(196, 69, 58, 0.06)"
              : "rgba(253, 252, 249, 0.88)",
            backdropFilter: "blur(16px) saturate(1.3)",
            WebkitBackdropFilter: "blur(16px) saturate(1.3)",
            boxShadow: "var(--shadow-sm)",
            cursor: userHovered ? "pointer" : "default",
            fontFamily: "var(--font-ui)",
            fontSize: "var(--font-size-xs)",
            fontWeight: 500,
            color: userHovered
              ? "var(--color-danger)"
              : "var(--color-text-secondary)",
            transition: "all var(--transition-fast)",
            minWidth: 0,
            whiteSpace: "nowrap",
          }}
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
        <div ref={notifContainerRef} style={{ position: "relative" }}>
          <button
            type="button"
            onClick={() => setNotifOpen(!notifOpen)}
            aria-label="通知"
            style={{
              position: "relative",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              width: 36,
              height: 36,
              border: "1px solid var(--color-border-default)",
              borderRadius: "50%",
              background: "rgba(253, 252, 249, 0.88)",
              backdropFilter: "blur(16px) saturate(1.3)",
              WebkitBackdropFilter: "blur(16px) saturate(1.3)",
              boxShadow: "var(--shadow-sm)",
              cursor: "pointer",
              padding: 0,
              transition: "all var(--transition-fast)",
            }}
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
              <span
                style={{
                  position: "absolute",
                  top: -2,
                  right: -2,
                  minWidth: 16,
                  height: 16,
                  borderRadius: "50%",
                  background: "var(--color-danger)",
                  color: "#fff",
                  fontSize: 10,
                  fontWeight: 700,
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                  padding: "0 4px",
                  lineHeight: 1,
                }}
              >
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
