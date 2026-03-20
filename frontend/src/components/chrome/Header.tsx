import { useRef, useCallback } from "react";
import { useSseStore } from "../../stores/sseStore";
import { useUiStore } from "../../stores/uiStore";
import { useAuthStore } from "../../stores/authStore";
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
  const sseStatus = useSseStore((s) => s.status);
  const headerExpanded = useUiStore((s) => s.headerExpanded);
  const setHeaderExpanded = useUiStore((s) => s.setHeaderExpanded);
  const roles = useAuthStore((s) => s.roles);
  const leaveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const handleEnter = useCallback(() => {
    if (leaveTimer.current) {
      clearTimeout(leaveTimer.current);
      leaveTimer.current = null;
    }
    setHeaderExpanded(true);
  }, [setHeaderExpanded]);

  const handleLeave = useCallback(() => {
    leaveTimer.current = setTimeout(() => setHeaderExpanded(false), 300);
  }, [setHeaderExpanded]);

  const topRole = roles.includes("ADMIN")
    ? "ADMIN"
    : roles.includes("AGENT")
      ? "AGENT"
      : "USER";

  return (
    <header
      onMouseEnter={handleEnter}
      onMouseLeave={handleLeave}
      style={{
        position: "fixed",
        top: 0,
        left: 0,
        zIndex: 100,
        display: "flex",
        alignItems: "center",
        gap: "var(--space-2)",
        padding: "var(--space-2) var(--space-4)",
        background: headerExpanded ? "rgba(252, 249, 242, 0.85)" : "transparent",
        backdropFilter: headerExpanded ? "blur(8px)" : "none",
        fontFamily: "var(--font-ui)",
        fontSize: "var(--font-size-sm)",
        transition: "all var(--transition-normal)",
        width: headerExpanded ? 260 : "auto",
        maxWidth: 260,
        boxSizing: "border-box",
        borderRight: headerExpanded ? "1px solid var(--color-border-default)" : "1px solid transparent",
        borderBottom: headerExpanded ? "1px solid var(--color-border-default)" : "1px solid transparent",
      }}
    >
      <span
        style={{
          flexShrink: 0,
          display: "flex",
          alignItems: "center",
          fontFamily: "var(--font-content)",
          fontWeight: 600,
          color: "var(--color-text-primary)",
          fontSize: "var(--font-size-lg)",
          letterSpacing: "-0.3px",
          lineHeight: 1,
        }}
      >
        <span
          style={{
            overflow: "hidden",
            transition: "max-width var(--transition-normal), opacity var(--transition-normal)",
            maxWidth: headerExpanded ? 0 : 100,
            opacity: headerExpanded ? 0 : 1,
            whiteSpace: "nowrap",
          }}
        >
          RhizoDelt
        </span>
        <span
          className={SSE_ANIM_CLASS[sseStatus]}
          aria-live="polite"
          style={{
            color: SSE_STATUS_COLOR[sseStatus],
            fontSize: "var(--font-size-xl)",
            lineHeight: 1,
            verticalAlign: "-1px",
            transition: "color var(--transition-normal)",
          }}
        >
          <span
            style={{
              position: "absolute",
              width: "1px",
              height: "1px",
              padding: 0,
              margin: "-1px",
              overflow: "hidden",
              clip: "rect(0, 0, 0, 0)",
              whiteSpace: "nowrap",
              borderWidth: 0,
            }}
          >
            {sseStatus === "connecting" && "连接中"}
            {sseStatus === "connected" && "已连接"}
            {sseStatus === "disconnected" && "连接已断开"}
          </span>
          <span aria-hidden="true">△</span>
        </span>
      </span>

      {headerExpanded && (
        <>
          <div style={{ display: "flex", flex: 1, minWidth: 0, overflow: "hidden", alignItems: "center" }}>
            <Breadcrumb />
          </div>
          <div style={{ marginLeft: "auto", flexShrink: 0 }}>
            <RoleBadge role={topRole} />
          </div>
        </>
      )}
    </header>
  );
}
