import { useState } from "react";
import { useUiStore } from "../../stores/uiStore";
import { useAuthStore } from "../../stores/authStore";
import { executeFork } from "../../api/decisions";
import { MarkdownEditor } from "../editor/MarkdownEditor";

interface Branch {
  id: string;
  content: string;
  author_id: string;
}

interface Props {
  sourceNodeId: string;
  onSuccess: () => void;
}

const INPUT_CLASS =
  "w-full px-3 py-2 border border-border-default rounded-sm font-ui text-sm bg-bg-primary";
const LABEL_CLASS = "block mb-1 font-ui text-xs text-text-secondary";

export function ForkForm({ sourceNodeId, onSuccess }: Props) {
  const [reason, setReason] = useState("");
  const [branches, setBranches] = useState<Branch[]>([
    { id: crypto.randomUUID(), content: "", author_id: "" },
  ]);
  const [submitting, setSubmitting] = useState(false);
  const addToast = useUiStore((s) => s.addToast);
  const userId = useAuthStore((s) => s.userId);

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
    if (branches.length <= 1) return;
    setBranches(branches.filter((_, i) => i !== idx));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (branches.some((b) => !b.content.replace(/<[^>]+>/g, "").trim())) {
      addToast({ type: "error", message: "所有分支内容不能为空" });
      return;
    }
    if (!userId) {
      addToast({ type: "error", message: "缺少当前登录用户身份" });
      return;
    }

    setSubmitting(true);
    try {
      await executeFork({
        operation_id: crypto.randomUUID(),
        request_id: crypto.randomUUID(),
        source_node_id: sourceNodeId,
        branches: branches.map((b) => ({
          decision_id: b.id,
          content: b.content,
          author_id: b.author_id || userId,
        })),
        operator_type: "HUMAN",
        operator_id: userId,
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

  return (
    <form
      onSubmit={handleSubmit}
      onKeyDown={(e) => e.stopPropagation()}
      className="flex flex-col gap-4"
    >
      <div>
        <label className={LABEL_CLASS}>分叉原因 *</label>
        <input
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder="为什么分叉"
          required
          className={INPUT_CLASS}
        />
      </div>

      <div className="flex flex-col gap-4">
        {branches.map((branch, idx) => (
          <div
            key={branch.id}
            className="border border-border-default rounded-sm p-3 flex flex-col gap-2"
          >
            <div className="flex justify-between items-center">
              <span className="font-ui text-xs font-medium">
                分支 {idx + 1}
              </span>
              {branches.length > 1 && (
                <button
                  type="button"
                  onClick={() => removeBranch(idx)}
                  className="bg-transparent border-none cursor-pointer text-danger text-xs"
                >
                  删除
                </button>
              )}
            </div>

            <MarkdownEditor
              value={branch.content}
              onChange={(val) => updateBranch(idx, "content", val)}
              minHeight={120}
            />

            <input
              value={branch.author_id}
              onChange={(e) => updateBranch(idx, "author_id", e.target.value)}
              placeholder="作者 ID（可选）"
              className={INPUT_CLASS}
            />
          </div>
        ))}
      </div>

      <button
        type="button"
        onClick={addBranch}
        className="bg-transparent border border-dashed border-border-default rounded-sm p-2 cursor-pointer font-ui text-sm text-text-secondary w-full"
      >
        + 添加分支
      </button>
      <div className="flex flex-col-reverse md:flex-row gap-3 justify-end mt-2">
        <button
          className="btn-secondary w-full md:w-auto py-3 md:py-2"
          type="button"
          onClick={onSuccess}
        >
          取消
        </button>
        <button
          className="btn-primary w-full md:w-auto py-3 md:py-2"
          type="submit"
          disabled={submitting || !userId}
        >
          {submitting ? "创建分叉中..." : "创建分叉"}
        </button>
      </div>
    </form>
  );
}
