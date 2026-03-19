export interface EmptyStateProps {
  message: string;
}

export function EmptyState({ message }: EmptyStateProps) {
  return (
    <div
      style={{
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        padding: "var(--space-8) var(--space-4)",
        fontFamily: "var(--font-ui)",
        fontSize: "var(--font-size-sm)",
        color: "var(--color-text-tertiary)",
        textAlign: "center",
      }}
    >
      {message}
    </div>
  );
}
