export interface RoleBadgeProps {
  role: "ADMIN" | "AGENT" | "USER";
}

const ROLE_CLASSES: Record<RoleBadgeProps["role"], string> = {
  ADMIN: "bg-[rgba(235,87,87,0.1)] text-danger",
  AGENT: "bg-[rgba(155,89,182,0.1)] text-node-consensus",
  USER: "bg-bg-hover text-text-secondary",
};

export function RoleBadge({ role }: RoleBadgeProps) {
  return (
    <span
      className={`inline-flex items-center py-[2px] px-2 rounded-full font-ui text-xs font-medium leading-[1.5] ${ROLE_CLASSES[role]}`}
    >
      {role}
    </span>
  );
}
