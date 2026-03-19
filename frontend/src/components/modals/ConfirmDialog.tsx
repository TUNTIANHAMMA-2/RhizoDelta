import { useEffect, useRef, type ReactNode } from "react";

export interface ConfirmDialogProps {
  isOpen: boolean;
  title: string;
  description: string | ReactNode;
  onConfirm: () => void | Promise<void>;
  onCancel: () => void;
  confirmText?: string;
  cancelText?: string;
  isDestructive?: boolean;
}

export function ConfirmDialog({
  isOpen,
  title,
  description,
  onConfirm,
  onCancel,
  confirmText = "确认",
  cancelText = "取消",
  isDestructive = false,
}: ConfirmDialogProps) {
  const cancelRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (isOpen) {
      cancelRef.current?.focus();
    }
  }, [isOpen]);

  useEffect(() => {
    if (!isOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onCancel();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [isOpen, onCancel]);

  if (!isOpen) return null;

  return (
    <div
      style={{
        position: "fixed",
        inset: 0,
        zIndex: 300,
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        background: "rgba(55, 53, 47, 0.4)",
      }}
      onClick={onCancel}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onClick={(e) => e.stopPropagation()}
        style={{
          background: "var(--color-bg-primary)",
          borderRadius: "var(--radius-lg)",
          boxShadow: "var(--shadow-lg)",
          padding: "var(--space-6)",
          maxWidth: 400,
          width: "90%",
          fontFamily: "var(--font-ui)",
        }}
      >
        <h3
          style={{
            margin: 0,
            marginBottom: "var(--space-3)",
            fontSize: "var(--font-size-md)",
            fontWeight: 600,
            color: isDestructive
              ? "var(--color-danger)"
              : "var(--color-text-primary)",
          }}
        >
          {title}
        </h3>
        <div
          style={{
            fontSize: "var(--font-size-sm)",
            color: "var(--color-text-secondary)",
            lineHeight: 1.5,
            marginBottom: "var(--space-6)",
          }}
        >
          {description}
        </div>
        <div
          style={{
            display: "flex",
            justifyContent: "flex-end",
            gap: "var(--space-3)",
          }}
        >
          <button
            ref={cancelRef}
            onClick={onCancel}
            style={{
              background: "none",
              border: "none",
              cursor: "pointer",
              padding: "var(--space-2) var(--space-4)",
              borderRadius: "var(--radius-sm)",
              fontFamily: "var(--font-ui)",
              fontSize: "var(--font-size-sm)",
              color: "var(--color-text-secondary)",
            }}
          >
            {cancelText}
          </button>
          <button
            onClick={onConfirm}
            style={{
              background: isDestructive
                ? "var(--color-danger)"
                : "var(--color-accent)",
              color: "#fff",
              border: "none",
              cursor: "pointer",
              padding: "var(--space-2) var(--space-4)",
              borderRadius: "var(--radius-sm)",
              fontFamily: "var(--font-ui)",
              fontSize: "var(--font-size-sm)",
              fontWeight: 500,
            }}
          >
            {confirmText}
          </button>
        </div>
      </div>
    </div>
  );
}
