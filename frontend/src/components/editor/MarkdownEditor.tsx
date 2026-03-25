import { useEditor, EditorContent } from "@tiptap/react";
import StarterKit from "@tiptap/starter-kit";
import Link from "@tiptap/extension-link";
import { Markdown } from "tiptap-markdown";
import { useEffect, useState } from "react";
import "../../styles/editor.css"; // We will create this

interface MarkdownEditorProps {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  minHeight?: number;
}

const MenuBar = ({ editor }: { editor: any }) => {
  if (!editor) {
    return null;
  }

  const btnStyle = (isActive: boolean) => ({
    background: isActive ? "var(--color-bg-hover)" : "transparent",
    border: "none",
    borderRadius: "var(--radius-sm)",
    padding: "var(--space-1) var(--space-2)",
    cursor: "pointer",
    fontSize: "var(--font-size-sm)",
    color: isActive ? "var(--color-text-primary)" : "var(--color-text-secondary)",
    fontWeight: isActive ? 600 : 400,
    fontFamily: "var(--font-ui)",
  });

  return (
    <div
      style={{
        display: "flex",
        flexWrap: "wrap",
        gap: "var(--space-1)",
        padding: "var(--space-2)",
        borderBottom: "1px solid var(--color-border-default)",
        background: "var(--color-bg-secondary)",
        borderTopLeftRadius: "var(--radius-sm)",
        borderTopRightRadius: "var(--radius-sm)",
      }}
    >
      <button
        type="button"
        onClick={() => editor.chain().focus().toggleBold().run()}
        style={btnStyle(editor.isActive("bold"))}
      >
        B
      </button>
      <button
        type="button"
        onClick={() => editor.chain().focus().toggleItalic().run()}
        style={btnStyle(editor.isActive("italic"))}
      >
        I
      </button>
      <button
        type="button"
        onClick={() => editor.chain().focus().toggleStrike().run()}
        style={btnStyle(editor.isActive("strike"))}
      >
        S
      </button>
      <span style={{ width: 1, background: "var(--color-border-default)", margin: "0 var(--space-1)" }} />
      <button
        type="button"
        onClick={() => editor.chain().focus().toggleHeading({ level: 1 }).run()}
        style={btnStyle(editor.isActive("heading", { level: 1 }))}
      >
        H1
      </button>
      <button
        type="button"
        onClick={() => editor.chain().focus().toggleHeading({ level: 2 }).run()}
        style={btnStyle(editor.isActive("heading", { level: 2 }))}
      >
        H2
      </button>
      <button
        type="button"
        onClick={() => editor.chain().focus().toggleHeading({ level: 3 }).run()}
        style={btnStyle(editor.isActive("heading", { level: 3 }))}
      >
        H3
      </button>
      <span style={{ width: 1, background: "var(--color-border-default)", margin: "0 var(--space-1)" }} />
      <button
        type="button"
        onClick={() => editor.chain().focus().toggleBulletList().run()}
        style={btnStyle(editor.isActive("bulletList"))}
      >
        UL
      </button>
      <button
        type="button"
        onClick={() => editor.chain().focus().toggleOrderedList().run()}
        style={btnStyle(editor.isActive("orderedList"))}
      >
        OL
      </button>
      <button
        type="button"
        onClick={() => editor.chain().focus().toggleBlockquote().run()}
        style={btnStyle(editor.isActive("blockquote"))}
      >
        Quote
      </button>
      <span style={{ width: 1, background: "var(--color-border-default)", margin: "0 var(--space-1)" }} />
      <button
        type="button"
        onClick={() => editor.chain().focus().toggleCodeBlock().run()}
        style={btnStyle(editor.isActive("codeBlock"))}
      >
        Code
      </button>
      <button
        type="button"
        onClick={() => editor.chain().focus().setHorizontalRule().run()}
        style={btnStyle(false)}
      >
        ---
      </button>
    </div>
  );
};

export function MarkdownEditor({ value, onChange, minHeight = 180 }: MarkdownEditorProps) {
  const [internalValue, setInternalValue] = useState(value);

  const editor = useEditor({
    extensions: [
      StarterKit.configure({
        heading: { levels: [1, 2, 3, 4] },
      }),
      Link.configure({
        openOnClick: false,
      }),
      Markdown,
    ],
    content: value,
    onUpdate: ({ editor }) => {
      const md = (editor.storage as any).markdown.getMarkdown();
      setInternalValue(md);
      onChange(md);
    },
    editorProps: {
      attributes: {
        class: "tiptap-editor-content",
        style: `min-height: ${minHeight}px; padding: var(--space-3); outline: none;`,
      },
    },
  });

  // Handle external value changes (e.g. form reset)
  useEffect(() => {
    if (editor && value !== internalValue) {
      editor.commands.setContent(value);
      setInternalValue(value);
    }
  }, [value, editor, internalValue]);

  return (
    <div
      style={{
        border: "1px solid var(--color-border-default)",
        borderRadius: "var(--radius-sm)",
        display: "flex",
        flexDirection: "column",
        background: "var(--color-bg-primary)",
        overflow: "hidden",
      }}
    >
      <MenuBar editor={editor} />
      <div
        className="rd-markdown-container"
        style={{
          flex: 1,
          overflowY: "auto",
          cursor: "text",
        }}
        onClick={() => editor?.commands.focus()}
      >
        <EditorContent editor={editor} />
      </div>
    </div>
  );
}
