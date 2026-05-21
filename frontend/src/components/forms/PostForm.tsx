import { useState } from "react";
import { useUiStore } from "../../stores/uiStore";
import { useGraphStore } from "../../stores/graphStore";
import { useAuthStore } from "../../stores/authStore";
import { createPost } from "../../api/posts";
import { MarkdownEditor } from "../editor/MarkdownEditor";

export function PostForm() {
  const [content, setContent] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const addToast = useUiStore((s) => s.addToast);
  const selectedNodeId = useGraphStore((s) => s.selectedNodeId);
  const selectedNode = useGraphStore((s) =>
    s.selectedNodeId ? s.nodes.get(s.selectedNodeId) ?? null : null,
  );
  const selectNode = useGraphStore((s) => s.selectNode);
  const userId = useAuthStore((s) => s.userId);
  const targetPreview =
    selectedNode?.summary_content ??
    selectedNode?.content ??
    (selectedNodeId ? `节点 ${selectedNodeId.slice(0, 8)}...` : null);
  const successMessage = selectedNodeId ? "回复已排队" : "发布已排队";
  const failurePrefix = selectedNodeId ? "回复失败: " : "发布失败: ";

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    const strippedContent = content.replace(/<[^>]+>/g, "").trim();
    if (!strippedContent) return;
    if (!userId) {
      addToast({ type: "error", message: "缺少当前登录用户身份" });
      return;
    }

    setSubmitting(true);
    try {
      await createPost({
        request_id: crypto.randomUUID(),
        author_id: userId,
        content,
        target_node_id: selectedNodeId ?? undefined,
      });
      addToast({ type: "success", message: successMessage });
      setContent("");
    } catch (err) {
      addToast({
        type: "error",
        message: failurePrefix + (err as Error).message,
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
      {selectedNodeId && (
        <div className="flex justify-between items-center gap-3 p-3 border border-border-default rounded-md bg-bg-primary">
          <div className="min-w-0">
            <div className="text-xs text-text-secondary mb-1">正在回复</div>
            <div className="text-sm text-text-primary whitespace-nowrap overflow-hidden text-ellipsis">
              {targetPreview}
            </div>
          </div>
          <button
            type="button"
            className="btn-secondary"
            onClick={() => selectNode(null)}
          >
            取消回复
          </button>
        </div>
      )}

      <MarkdownEditor
        value={content}
        onChange={(val) => setContent(val)}
        minHeight={200}
      />

      <div className="flex justify-end mt-2">
        <button
          className="btn-primary w-full md:w-auto py-3 md:py-2"
          type="submit"
          disabled={submitting || !userId || !content.replace(/<[^>]+>/g, "").trim()}
        >
          {submitting ? "发布中..." : selectedNodeId ? "回复" : "发布"}
        </button>
      </div>
    </form>
  );
}
