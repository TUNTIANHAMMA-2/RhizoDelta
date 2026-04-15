import { useSseStore } from "../../stores/sseStore";
import { useAuthStore } from "../../stores/authStore";
import { useUiStore } from "../../stores/uiStore";
import { useGraphStore } from "../../stores/graphStore";
import { useNavigate } from "react-router-dom";
import { Breadcrumb } from "./Breadcrumb";
import { RoleBadge } from "./RoleBadge";

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

  const topRole = roles.includes("ADMIN")
    ? "ADMIN"
    : roles.includes("AGENT")
      ? "AGENT"
      : "USER";

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
        {/* Logo 胶囊 (紧凑尺寸) */}
        <div
          style={{
            display: "flex",
            alignItems: "center",
            height: 40,
            background: "rgba(255, 255, 255, 0.85)",
            backdropFilter: "blur(12px)",
            WebkitBackdropFilter: "blur(12px)",
            border: "1px solid var(--color-border-default)",
            borderRadius: "var(--radius-lg)",
            padding: "0 var(--space-4)",
            boxShadow: "var(--shadow-md)",
            pointerEvents: "auto",
            width: "max-content",
            boxSizing: "border-box",
            zIndex: 2, // 确保 Logo 处于上层，面包屑从其下方滑出
          }}
        >
          <div
            style={{
              flexShrink: 0,
              display: "flex",
              alignItems: "center",
              fontFamily: "var(--font-content)",
              fontWeight: 600,
              color: "var(--color-text-primary)",
              fontSize: "var(--font-size-md)",
              letterSpacing: "-0.2px",
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
              ? (leftSidebarOpen ? 260 : 156) // 260 = 276(绝对坐标) - 16(相对父级); 156 = Logo宽度(~140) + 16px间隙
              : 80, // 收起时缩入 Logo 背后
            display: "flex",
            alignItems: "center",
            height: 40,
            background: "rgba(255, 255, 255, 0.85)",
            backdropFilter: "blur(12px)",
            WebkitBackdropFilter: "blur(12px)",
            border: selectedNodeId ? "1px solid var(--color-border-default)" : "1px solid transparent",
            borderRadius: "var(--radius-lg)",
            padding: selectedNodeId ? "0 var(--space-4)" : "0",
            boxShadow: selectedNodeId ? "var(--shadow-md)" : "none",
            pointerEvents: selectedNodeId ? "auto" : "none",
            opacity: selectedNodeId ? 1 : 0,
            maxWidth: selectedNodeId ? 400 : 0,
            overflow: "hidden",
            transition: "all 300ms cubic-bezier(0.4, 0, 0.2, 1)",
            zIndex: 1,
          }}
        >
          {selectedNodeId && <Breadcrumb />}
        </div>
      </div>

      <div 
        style={{ 
          pointerEvents: "auto",
          display: "flex",
          alignItems: "center",
          gap: "var(--space-3)",
          transition: "transform 300ms cubic-bezier(0.4, 0, 0.2, 1)",
          transform: rightPanelMode !== "hidden" ? "translateX(calc(-1 * max(45vw, 460px)))" : "translateX(0)",
        }}
      >
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: "var(--space-2)",
            padding: "6px 8px 6px 14px",
            borderRadius: "999px",
            background: "rgba(255, 255, 255, 0.85)",
            border: "1px solid var(--color-border-default)",
            boxShadow: "var(--shadow-md)",
          }}
        >
          <span style={{ color: "var(--color-text-secondary)" }}>
            {username ?? userId ?? "anonymous"}
          </span>
          {topRole === "ADMIN" && (
            <button
              type="button"
              onClick={openReviewPanel}
              style={{
                border: "none",
                borderRadius: "999px",
                padding: "6px 10px",
                background: "var(--color-bg-tertiary)",
                color: "var(--color-text-primary)",
                cursor: "pointer",
                fontFamily: "var(--font-ui)",
                fontSize: "var(--font-size-xs)",
                fontWeight: 600,
              }}
            >
              复核
            </button>
          )}
          <button
            type="button"
            onClick={() => {
              clearToken();
              navigate("/login", { replace: true });
            }}
            style={{
              border: "none",
              borderRadius: "999px",
              padding: "6px 10px",
              background: "var(--color-bg-tertiary)",
              color: "var(--color-text-primary)",
              cursor: "pointer",
              fontFamily: "var(--font-ui)",
              fontSize: "var(--font-size-xs)",
              fontWeight: 600,
            }}
          >
            退出
          </button>
        </div>
        <RoleBadge role={topRole} />
      </div>
    </header>
  );
}
