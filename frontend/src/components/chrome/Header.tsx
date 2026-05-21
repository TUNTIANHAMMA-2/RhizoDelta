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

function useIsDesktop() {
  const [isDesktop, setIsDesktop] = useState(() =>
    typeof window === "undefined" ? true : window.matchMedia("(min-width: 768px)").matches,
  );
  useEffect(() => {
    if (typeof window === "undefined") return;
    const mql = window.matchMedia("(min-width: 768px)");
    const handler = (e: MediaQueryListEvent) => setIsDesktop(e.matches);
    mql.addEventListener("change", handler);
    return () => mql.removeEventListener("change", handler);
  }, []);
  return isDesktop;
}

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

const CAPSULE_SURFACE =
  "bg-[rgba(253,252,249,0.88)] backdrop-blur-md backdrop-saturate-150 border border-border-default shadow-sm";

/**
 * User and Notification controls, shared between fixed and embedded headers.
 */
export function HeaderActions({ isDesktop }: { isDesktop: boolean }) {
  const navigate = useNavigate();
  const roles = useAuthStore((s) => s.roles);
  const userId = useAuthStore((s) => s.userId);
  const username = useAuthStore((s) => s.username);
  const clearToken = useAuthStore((s) => s.clearToken);
  const rightPanelMode = useUiStore((s) => s.rightPanelMode);
  const openReviewPanel = useUiStore((s) => s.openReviewPanel);
  const unreadCount = useNotificationStore((s) => s.unreadCount);
  const [notifOpen, setNotifOpen] = useState(false);
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  const notifContainerRef = useRef<HTMLDivElement>(null);
  const userMenuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const onMouseDown = (e: MouseEvent) => {
      if (notifOpen && notifContainerRef.current && !notifContainerRef.current.contains(e.target as Node)) {
        setNotifOpen(false);
      }
      if (userMenuOpen && userMenuRef.current && !userMenuRef.current.contains(e.target as Node)) {
        setUserMenuOpen(false);
      }
    };
    document.addEventListener("mousedown", onMouseDown);
    return () => document.removeEventListener("mousedown", onMouseDown);
  }, [notifOpen, userMenuOpen]);

  const topRole = roles.includes("ADMIN") ? "ADMIN" : roles.includes("AGENT") ? "AGENT" : "USER";

  const handleLogout = useCallback(() => {
    clearToken();
    navigate("/login", { replace: true });
  }, [clearToken, navigate]);

  return (
    <div
      className="pointer-events-auto flex items-center gap-2 transition-transform duration-300 ease-[var(--ease-out)]"
      style={{
        transform:
          isDesktop && rightPanelMode === "edit"
            ? "translateX(calc(-1 * min(max(50vw, 520px), 920px)))"
            : isDesktop && rightPanelMode !== "hidden"
            ? "translateX(calc(-1 * min(max(38vw, 420px), 720px)))"
            : "translateX(0)",
      }}
    >
      {topRole === "ADMIN" && (
        <button
          type="button"
          onClick={openReviewPanel}
          className={clsx(
            CAPSULE_SURFACE,
            "hidden md:flex items-center h-9 rounded-pill px-[14px] cursor-pointer font-ui text-xs font-semibold text-text-secondary transition-all tracking-[0.01em]",
          )}
        >
          复核
        </button>
      )}

      <div ref={userMenuRef} className="relative">
        <button
          type="button"
          onClick={() => setUserMenuOpen(!userMenuOpen)}
          className={clsx(
            "flex items-center gap-2 h-9 rounded-pill font-ui text-xs font-medium transition-all min-w-0 whitespace-nowrap cursor-pointer",
            "md:px-[14px] px-2 md:backdrop-blur-md md:backdrop-saturate-150 md:shadow-sm",
            userMenuOpen
              ? "border border-border-focus bg-[rgba(253,252,249,0.95)] text-text-primary"
              : "border border-transparent md:border-border-default bg-transparent md:bg-[rgba(253,252,249,0.88)] text-text-secondary md:hover:bg-[rgba(253,252,249,0.95)] hover:text-text-primary",
          )}
        >
          {isDesktop && <RoleBadge role={topRole} />}
          <span className="inline">{username ?? userId ?? "anonymous"}</span>
        </button>

        {userMenuOpen && (
          <div className="absolute top-full right-0 mt-2 w-40 bg-bg-primary border border-border-default rounded-md shadow-lg z-[200] font-ui text-sm py-1">
            <div className="px-4 py-2 border-b border-border-subtle mb-1">
              <div className="font-semibold text-text-primary truncate">{username ?? "User"}</div>
              <div className="text-text-tertiary text-xs truncate mt-0.5">{userId}</div>
            </div>
            <button
              type="button"
              className="w-full text-left px-4 py-2 hover:bg-bg-hover text-text-secondary transition-colors"
              onClick={() => {
                setUserMenuOpen(false);
                useUiStore.getState().addToast({ type: "info", message: "用户资料页即将上线" });
              }}
            >
              用户资料
            </button>
            <button
              type="button"
              className="w-full text-left px-4 py-2 hover:bg-bg-hover text-text-secondary transition-colors"
              onClick={() => {
                setUserMenuOpen(false);
                navigate("/settings");
              }}
            >
              设置
            </button>
            <div className="h-px bg-border-subtle my-1" />
            <button
              type="button"
              className="w-full text-left px-4 py-2 hover:bg-danger/10 text-danger transition-colors"
              onClick={handleLogout}
            >
              退出登录
            </button>
          </div>
        )}
      </div>

      <div ref={notifContainerRef} className="relative">
        <button
          type="button"
          onClick={() => setNotifOpen(!notifOpen)}
          className={clsx(
            CAPSULE_SURFACE,
            "relative flex items-center justify-center w-9 h-9 rounded-full cursor-pointer p-0 transition-all",
          )}
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="var(--color-text-secondary)" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
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
  );
}

export function Header({
  hideLogo = false,
  embedded = false,
}: {
  hideLogo?: boolean;
  embedded?: boolean;
} = {}) {
  const navigate = useNavigate();
  const location = useLocation();
  const sseStatus = useSseStore((s) => s.status);
  const leftSidebarOpen = useUiStore((s) => s.leftSidebarOpen);
  const isMobileMenuOpen = useUiStore((s) => s.isMobileMenuOpen);
  const setMobileMenuOpen = useUiStore((s) => s.setMobileMenuOpen);
  const selectedNodeId = useGraphStore((s) => s.selectedNodeId);
  const selectNode = useGraphStore((s) => s.selectNode);
  const closeRightPanel = useUiStore((s) => s.closeRightPanel);
  const isDesktop = useIsDesktop();

  const onWorkspace = location.pathname.startsWith("/workspace");

  const handleBackHome = useCallback(() => {
    selectNode(null);
    closeRightPanel();
    navigate("/");
  }, [selectNode, closeRightPanel, navigate]);

  if (embedded) {
    return (
      <div className="w-full px-4 md:px-8 py-2">
        <div className="w-full flex items-center justify-between gap-4">
          {/* Mobile Menu Toggle */}
          <button
            type="button"
            onClick={() => setMobileMenuOpen(!isMobileMenuOpen)}
            className="md:hidden w-10 h-10 flex items-center justify-center text-text-secondary -ml-2 transition-colors hover:text-text-primary"
            aria-label="菜单"
          >
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <line x1="4" x2="20" y1="12" y2="12" />
              <line x1="4" x2="20" y1="6" y2="6" />
              <line x1="4" x2="20" y1="18" y2="18" />
            </svg>
          </button>

          {/* Spacer for mobile/desktop to push actions to right if no logo here */}
          <div className="flex-1" />

          {/* User Actions - Mobile Only in embedded home page */}
          <div className="md:hidden">
            <HeaderActions isDesktop={isDesktop} />
          </div>
        </div>
      </div>
    );
  }

  return (
    <header className="fixed top-0 left-0 z-[100] flex items-start justify-between w-full p-4 pointer-events-none font-ui text-sm box-border">
      <div className="flex items-center relative w-full h-10">
        {!hideLogo && (
          <div
            className={clsx(
              CAPSULE_SURFACE,
              "flex items-center h-10 rounded-md px-4 shadow-md pointer-events-auto w-max box-border z-[2]",
              onWorkspace && "cursor-pointer group",
            )}
            onClick={onWorkspace ? handleBackHome : undefined}
          >
            {onWorkspace && <span className="mr-2 text-text-tertiary group-hover:text-accent transition-colors">←</span>}
            <div className="shrink-0 flex items-center font-content font-normal text-text-primary text-md tracking-[-0.02em]">
              RhizoDelt
              <span
                className={`${SSE_ANIM_CLASS[sseStatus as keyof typeof SSE_ANIM_CLASS]} text-lg ml-[2px]`}
                style={{ color: SSE_STATUS_COLOR[sseStatus as keyof typeof SSE_STATUS_COLOR] }}
              >
                △
              </span>
            </div>
          </div>
        )}

        <div
          className={clsx(
            "absolute top-0 hidden md:flex items-center h-10 rounded-md overflow-hidden transition-all duration-300 ease-[var(--ease-out)] z-[1]",
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

      <HeaderActions isDesktop={isDesktop} />
    </header>
  );
}
