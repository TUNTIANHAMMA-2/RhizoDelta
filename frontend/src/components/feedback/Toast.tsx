import { useUiStore, type ToastMessage } from "../../stores/uiStore";

const TYPE_BORDER_COLOR: Record<ToastMessage["type"], string> = {
  info: "var(--color-accent)",
  success: "var(--color-success)",
  warning: "var(--color-warning)",
  error: "var(--color-danger)",
};

function ToastItem({ toast }: { toast: ToastMessage }) {
  const removeToast = useUiStore((s) => s.removeToast);

  return (
    <div
      role="alert"
      style={{
        display: "flex",
        alignItems: "center",
        gap: "var(--space-3)",
        padding: "var(--space-3) var(--space-4)",
        background: "var(--color-bg-primary)",
        borderLeft: `4px solid ${TYPE_BORDER_COLOR[toast.type]}`,
        borderRadius: "var(--radius-sm)",
        boxShadow: "var(--shadow-md)",
        fontFamily: "var(--font-ui)",
        fontSize: "var(--font-size-sm)",
        color: "var(--color-text-primary)",
        marginTop: "var(--space-2)",
        animation: "toast-slide-in 200ms ease",
      }}
    >
      <span style={{ flex: 1 }}>{toast.message}</span>
      <button
        onClick={() => removeToast(toast.id)}
        style={{
          background: "none",
          border: "none",
          cursor: "pointer",
          color: "var(--color-text-tertiary)",
          fontSize: "var(--font-size-md)",
          lineHeight: 1,
          padding: 0,
        }}
        aria-label="关闭"
      >
        &times;
      </button>
    </div>
  );
}

export function ToastContainer() {
  const toasts = useUiStore((s) => s.toasts);

  if (toasts.length === 0) return null;

  return (
    <>
      <style>{`
        @keyframes toast-slide-in {
          from { transform: translateX(100%); opacity: 0; }
          to { transform: translateX(0); opacity: 1; }
        }
      `}</style>
      <div
        aria-live="polite"
        style={{
          position: "fixed",
          bottom: "var(--space-6)",
          right: "var(--space-6)",
          zIndex: 200,
          display: "flex",
          flexDirection: "column",
          maxWidth: 360,
          minWidth: 280,
        }}
      >
        {toasts.map((t) => (
          <ToastItem key={t.id} toast={t} />
        ))}
      </div>
    </>
  );
}
