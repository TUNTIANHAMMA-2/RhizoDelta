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
    
    // 校验去除空壳后的内容
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
      style={{ display: "flex", flexDirection: "column", gap: "var(--space-4)" }}
    >
      {selectedNodeId && (
        <div
          style={{
            display: "flex",
            justifyContent: "space-between",
            alignItems: "center",
            gap: "var(--space-3)",
            padding: "var(--space-3)",
            border: "1px solid var(--color-border-default)",
            borderRadius: "var(--radius-md)",
            background: "var(--color-bg-primary)",
          }}
        >
          <div style={{ minWidth: 0 }}>
            <div
              style={{
                fontSize: "var(--font-size-xs)",
                color: "var(--color-text-secondary)",
                marginBottom: "var(--space-1)",
              }}
            >
              正在回复
            </div>
            <div
              style={{
                fontSize: "var(--font-size-sm)",
                color: "var(--color-text-primary)",
                whiteSpace: "nowrap",
                overflow: "hidden",
                textOverflow: "ellipsis",
              }}
            >
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

      <div style={{ display: "flex", justifyContent: "flex-end" }}>
        <button
          className="btn-primary"
          type="submit"
          disabled={submitting || !userId || !content.replace(/<[^>]+>/g, "").trim()}
        >
          {submitting ? "发布中..." : selectedNodeId ? "回复" : "发布"}
        </button>
      </div>
    </form>
  );
}
