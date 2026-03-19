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
        gap: "var(--space-3)",
        padding: "var(--space-2) var(--space-4)",
        background: "transparent",
        fontFamily: "var(--font-ui)",
        fontSize: "var(--font-size-sm)",
        transition: "all var(--transition-normal)",
      }}
    >
      <span
        style={{
          fontFamily: "var(--font-content)",
          fontWeight: 600,
          color: "var(--color-text-primary)",
          fontSize: 18,
          letterSpacing: "-0.3px",
          lineHeight: 1,
        }}
      >
        RhizoDelt
        <span
          className={SSE_ANIM_CLASS[sseStatus]}
          style={{
            color: SSE_STATUS_COLOR[sseStatus],
            fontSize: 22,
            lineHeight: 1,
            verticalAlign: "-1px",
            transition: "color var(--transition-normal)",
          }}
        >
          △
        </span>
      </span>

      {headerExpanded && (
        <>
          <Breadcrumb />
          <div style={{ marginLeft: "auto" }}>
            <RoleBadge role={topRole} />
          </div>
        </>
      )}
    </header>
  );
}
