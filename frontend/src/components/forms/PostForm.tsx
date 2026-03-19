import { useState } from "react";
import MDEditor from "@uiw/react-md-editor";
import rehypeSanitize from "rehype-sanitize";
import { useUiStore } from "../../stores/uiStore";
import { useGraphStore } from "../../stores/graphStore";
import { createPost } from "../../api/posts";

export function PostForm() {
  const [content, setContent] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const addToast = useUiStore((s) => s.addToast);
  const selectedNodeId = useGraphStore((s) => s.selectedNodeId);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!content.trim()) return;

    setSubmitting(true);
    try {
      await createPost({
        request_id: crypto.randomUUID(),
        author_id: "current_user",
        content,
        target_node_id: selectedNodeId ?? undefined,
      });
      addToast({ type: "success", message: "发布已排队" });
      setContent("");
    } catch (err) {
      addToast({
        type: "error",
        message: "发布失败: " + (err as Error).message,
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
            fontSize: "var(--font-size-xs)",
            color: "var(--color-text-secondary)",
          }}
        >
          回复节点: {selectedNodeId.slice(0, 8)}...
        </div>
      )}
      <div
        style={{
          border: "1px solid var(--color-border-default)",
          borderRadius: "var(--radius-sm)",
        }}
      >
        <MDEditor
          value={content}
          onChange={(val) => setContent(val ?? "")}
          preview="edit"
          height={200}
          previewOptions={{ rehypePlugins: [[rehypeSanitize]] }}
        />
      </div>
      <div style={{ display: "flex", justifyContent: "flex-end" }}>
        <button
          type="submit"
          disabled={submitting || !content.trim()}
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
            opacity: submitting || !content.trim() ? 0.6 : 1,
          }}
        >
          {submitting ? "发布中..." : "发布"}
        </button>
      </div>
    </form>
  );
}
