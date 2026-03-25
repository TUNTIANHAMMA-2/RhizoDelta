import { useState } from "react";
import { useUiStore } from "../../stores/uiStore";
import { useAuthStore } from "../../stores/authStore";
import { executeInject } from "../../api/decisions";
import { MarkdownEditor } from "../editor/MarkdownEditor";

interface Props {
  sourceNodeId: string;
  onSuccess: () => void;
}

export function InjectForm({ sourceNodeId, onSuccess }: Props) {
  const [content, setContent] = useState("");
  const [reason, setReason] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const addToast = useUiStore((s) => s.addToast);
  const userId = useAuthStore((s) => s.userId);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const strippedContent = content.replace(/<[^>]+>/g, "").trim();
    if (!strippedContent || !reason.trim()) return;
    if (!userId) {
      addToast({ type: "error", message: "缺少当前登录用户身份" });
      return;
    }

    setSubmitting(true);
    try {
      await executeInject({
        decision_id: crypto.randomUUID(),
        request_id: crypto.randomUUID(),
        source_node_id: sourceNodeId,
        content,
        author_id: userId,
        operator_type: "HUMAN",
        operator_id: userId,
        reason,
      });
      addToast({ type: "info", message: "注入决策已提交，等待处理..." });
      onSuccess();
    } catch (err) {
      addToast({
        type: "error",
        message: "注入失败: " + (err as Error).message,
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
          来源节点
        </label>
        <input
          value={sourceNodeId}
          readOnly
          style={{ ...inputStyle, color: "var(--color-text-tertiary)" }}
        />
      </div>
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
          延续原因 *
        </label>
        <input
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder="为什么延续这个观点"
          required
          style={inputStyle}
        />
      </div>
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
          内容 *
        </label>
        <MarkdownEditor
          value={content}
          onChange={(val) => setContent(val)}
          minHeight={180}
        />
      </div>
      <div style={{ display: "flex", gap: "var(--space-3)", justifyContent: "flex-end" }}>
        <button
          className="btn-secondary"
          type="button"
          onClick={onSuccess}
        >
          取消
        </button>
        <button
          className="btn-primary"
          type="submit"
          disabled={submitting || !userId}
        >
          {submitting ? "注入中..." : "注入"}
        </button>
      </div>
    </form>
  );
}
