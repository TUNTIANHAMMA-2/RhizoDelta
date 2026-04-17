import { useState } from "react";
import { useUiStore } from "../../stores/uiStore";
import { useAuthStore } from "../../stores/authStore";
import { executeInject } from "../../api/decisions";
import { MarkdownEditor } from "../editor/MarkdownEditor";

interface Props {
  sourceNodeId: string;
  onSuccess: () => void;
}

const INPUT_CLASS =
  "w-full px-3 py-2 border border-border-default rounded-sm font-ui text-sm bg-bg-primary";
const LABEL_CLASS =
  "block mb-1 font-ui text-xs text-text-secondary";

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

  return (
    <form
      onSubmit={handleSubmit}
      onKeyDown={(e) => e.stopPropagation()}
      className="flex flex-col gap-4"
    >
      <div>
        <label className={LABEL_CLASS}>来源节点</label>
        <input
          value={sourceNodeId}
          readOnly
          className={`${INPUT_CLASS} text-text-tertiary`}
        />
      </div>
      <div>
        <label className={LABEL_CLASS}>延续原因 *</label>
        <input
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder="为什么延续这个观点"
          required
          className={INPUT_CLASS}
        />
      </div>
      <div>
        <label className={LABEL_CLASS}>内容 *</label>
        <MarkdownEditor
          value={content}
          onChange={(val) => setContent(val)}
          minHeight={180}
        />
      </div>
      <div className="flex gap-3 justify-end">
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
