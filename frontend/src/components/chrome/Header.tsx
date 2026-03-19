import { useSseStore } from "../../stores/sseStore";
import { useUiStore } from "../../stores/uiStore";
import { useAuthStore } from "../../stores/authStore";
import { Breadcrumb } from "./Breadcrumb";
import { RoleBadge } from "./RoleBadge";

export function Header() {
  const sseStatus = useSseStore((s) => s.status);
  const headerExpanded = useUiStore((s) => s.headerExpanded);
  const setHeaderExpanded = useUiStore((s) => s.setHeaderExpanded);
  const roles = useAuthStore((s) => s.roles);

  const statusColor = {
    connecting: "var(--color-warning)",
    connected: "var(--color-success)",
    disconnected: "var(--color-danger)",
  }[sseStatus];

  const topRole = roles.includes("ADMIN")
    ? "ADMIN"
    : roles.includes("AGENT")
      ? "AGENT"
      : "USER";

  return (
    <header
      onMouseEnter={() => setHeaderExpanded(true)}
      onMouseLeave={() => setTimeout(() => setHeaderExpanded(false), 300)}
      style={{
        position: "fixed",
        top: 0,
        left: 0,
        zIndex: 100,
        display: "flex",
        alignItems: "center",
        gap: "var(--space-3)",
        padding: "var(--space-2) var(--space-4)",
        background: "rgba(250, 250, 248, 0.85)",
        backdropFilter: "blur(8px)",
        borderRadius: "0 0 var(--radius-md) 0",
        fontFamily: "var(--font-ui)",
        fontSize: "var(--font-size-sm)",
        transition: "all var(--transition-normal)",
        maxWidth: headerExpanded ? "100%" : "auto",
      }}
    >
      <span
        style={{
          color: statusColor,
          fontSize: "var(--font-size-lg)",
          transition: "color var(--transition-normal)",
        }}
      >
        △
      </span>
      <span style={{ fontWeight: 600, color: "var(--color-text-primary)" }}>
        RhizoDelta
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
