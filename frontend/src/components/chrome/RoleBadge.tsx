export interface RoleBadgeProps {
  role: "ADMIN" | "AGENT" | "USER";
}

const ROLE_STYLES: Record<
  RoleBadgeProps["role"],
  { bg: string; color: string }
> = {
  ADMIN: { bg: "rgba(235, 87, 87, 0.1)", color: "var(--color-danger)" },
  AGENT: { bg: "rgba(155, 89, 182, 0.1)", color: "var(--color-node-consensus)" },
  USER: { bg: "var(--color-bg-hover)", color: "var(--color-text-secondary)" },
};

export function RoleBadge({ role }: RoleBadgeProps) {
  const style = ROLE_STYLES[role];

  return (
    <span
      style={{
        display: "inline-flex",
        alignItems: "center",
        padding: "2px var(--space-2)",
        borderRadius: "var(--radius-full)",
        background: style.bg,
        color: style.color,
        fontFamily: "var(--font-ui)",
        fontSize: "var(--font-size-xs)",
        fontWeight: 500,
        lineHeight: 1.5,
      }}
    >
      {role}
    </span>
  );
}
