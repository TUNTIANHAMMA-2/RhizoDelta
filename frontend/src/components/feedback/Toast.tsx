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
      className="flex items-center gap-3 px-4 py-3 bg-bg-elevated rounded-md shadow-lg font-ui text-sm text-text-primary mt-2 backdrop-blur-md"
      style={{
        borderLeft: `3px solid ${TYPE_BORDER_COLOR[toast.type]}`,
        animation: "toast-slide-in 280ms var(--ease-out)",
      }}
    >
      <span className="flex-1">{toast.message}</span>
      <button
        onClick={() => removeToast(toast.id)}
        className="bg-transparent border-none cursor-pointer text-text-tertiary text-md leading-none p-0"
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
          from { transform: translateX(100%) scale(0.95); opacity: 0; }
          to { transform: translateX(0) scale(1); opacity: 1; }
        }
      `}</style>
      <div
        aria-live="polite"
        className="fixed bottom-6 right-6 z-[200] flex flex-col max-w-[360px] min-w-[280px]"
      >
        {toasts.map((t) => (
          <ToastItem key={t.id} toast={t} />
        ))}
      </div>
    </>
  );
}
