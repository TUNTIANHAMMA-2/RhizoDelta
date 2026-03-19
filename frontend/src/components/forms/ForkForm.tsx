import { useState } from "react";
import { useUiStore } from "../../stores/uiStore";
import { useAuthStore } from "../../stores/authStore";
import { executeFork } from "../../api/decisions";

interface Branch {
  id: string;
  content: string;
  author_id: string;
}

interface Props {
  sourceNodeId: string;
  onSuccess: () => void;
}

export function ForkForm({ sourceNodeId, onSuccess }: Props) {
  const [reason, setReason] = useState("");
  const [branches, setBranches] = useState<Branch[]>([
    { id: crypto.randomUUID(), content: "", author_id: "" },
    { id: crypto.randomUUID(), content: "", author_id: "" },
  ]);
  const [submitting, setSubmitting] = useState(false);
  const addToast = useUiStore((s) => s.addToast);
  const token = useAuthStore((s) => s.token);

  const updateBranch = (idx: number, field: keyof Branch, value: string) => {
    const next = [...branches];
    next[idx] = { ...next[idx], [field]: value };
    setBranches(next);
  };

  const addBranch = () =>
    setBranches([
      ...branches,
      { id: crypto.randomUUID(), content: "", author_id: "" },
    ]);

  const removeBranch = (idx: number) => {
    if (branches.length <= 2) return;
    setBranches(branches.filter((_, i) => i !== idx));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (branches.some((b) => !b.content.trim())) {
      addToast({ type: "error", message: "所有分支内容不能为空" });
      return;
    }

    setSubmitting(true);
    try {
      let operatorId = "unknown";
      if (token) {
        try {
          operatorId = JSON.parse(atob(token.split(".")[1])).sub ?? "unknown";
        } catch { /* fallback */ }
      }

      await executeFork({
        operation_id: crypto.randomUUID(),
        request_id: crypto.randomUUID(),
        source_node_id: sourceNodeId,
        branches: branches.map((b) => ({
          decision_id: b.id,
          content: b.content,
          author_id: b.author_id || operatorId,
        })),
        operator_type: "HUMAN",
        operator_id: operatorId,
        reason,
      });
      addToast({ type: "info", message: "分叉决策已提交" });
      onSuccess();
    } catch (err) {
      addToast({
        type: "error",
        message: "分叉失败: " + (err as Error).message,
      });
    } finally {
      setSubmitting(false);
    }
  };

  const inputStyle: React.CSSProperties = {
    width: "100%",
    padding: "var(--space-2) var(--space-3)",
    border: "1px solid var(--color-border-default)",
    borderRadius: "var(--radius-sm)",
    fontFamily: "var(--font-ui)",
    fontSize: "var(--font-size-sm)",
    background: "var(--color-bg-primary)",
  };

  return (
    <form
      onSubmit={handleSubmit}
      onKeyDown={(e) => e.stopPropagation()}
      style={{ display: "flex", flexDirection: "column", gap: "var(--space-4)" }}
    >
      <div>
        <label
          style={{
            display: "block",
            marginBottom: "var(--space-1)",
            fontFamily: "var(--font-ui)",
            fontSize: "var(--font-size-xs)",
            color: "var(--color-text-secondary)",
          }}
        >
          分叉原因 *
        </label>
        <input
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder="为什么分叉"
          required
          style={inputStyle}
        />
      </div>

      <div style={{ display: "flex", flexDirection: "column", gap: "var(--space-3)" }}>
        {branches.map((branch, idx) => (
          <div
            key={branch.id}
            style={{
              border: "1px solid var(--color-border-default)",
              borderRadius: "var(--radius-sm)",
              padding: "var(--space-3)",
            }}
          >
            <div
              style={{
                display: "flex",
                justifyContent: "space-between",
                alignItems: "center",
                marginBottom: "var(--space-2)",
              }}
            >
              <span
                style={{
                  fontFamily: "var(--font-ui)",
                  fontSize: "var(--font-size-xs)",
                  fontWeight: 500,
                }}
              >
                分支 {idx + 1}
              </span>
              {branches.length > 2 && (
                <button
                  type="button"
                  onClick={() => removeBranch(idx)}
                  style={{
                    background: "none",
                    border: "none",
                    cursor: "pointer",
                    color: "var(--color-danger)",
                    fontSize: "var(--font-size-xs)",
                  }}
                >
                  删除
                </button>
              )}
            </div>
            <textarea
              value={branch.content}
              onChange={(e) => updateBranch(idx, "content", e.target.value)}
              placeholder="分支内容..."
              required
              rows={3}
              style={{
                ...inputStyle,
                resize: "vertical",
                marginBottom: "var(--space-2)",
              }}
            />
            <input
              value={branch.author_id}
              onChange={(e) => updateBranch(idx, "author_id", e.target.value)}
              placeholder="作者 ID（可选）"
              style={inputStyle}
            />
          </div>
        ))}
      </div>

      <button
        type="button"
        onClick={addBranch}
        style={{
          background: "none",
          border: "1px dashed var(--color-border-default)",
          borderRadius: "var(--radius-sm)",
          padding: "var(--space-2)",
          cursor: "pointer",
          fontFamily: "var(--font-ui)",
          fontSize: "var(--font-size-sm)",
          color: "var(--color-text-secondary)",
        }}
      >
        + 添加分支
      </button>

      <div style={{ display: "flex", gap: "var(--space-3)", justifyContent: "flex-end" }}>
        <button
          type="button"
          onClick={onSuccess}
          style={{
            background: "none",
            border: "none",
            cursor: "pointer",
            color: "var(--color-text-secondary)",
            fontFamily: "var(--font-ui)",
            fontSize: "var(--font-size-sm)",
          }}
        >
          取消
        </button>
        <button
          type="submit"
          disabled={submitting}
          style={{
            background: "var(--color-accent)",
            color: "#fff",
            border: "none",
            padding: "var(--space-2) var(--space-4)",
            borderRadius: "var(--radius-sm)",
            fontFamily: "var(--font-ui)",
            fontSize: "var(--font-size-sm)",
            fontWeight: 500,
            cursor: submitting ? "not-allowed" : "pointer",
            opacity: submitting ? 0.6 : 1,
          }}
        >
          {submitting ? "提交中..." : "创建分叉"}
        </button>
      </div>
    </form>
  );
}
