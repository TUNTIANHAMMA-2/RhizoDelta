import { useEditor, EditorContent } from "@tiptap/react";
import type { Editor } from "@tiptap/core";
import StarterKit from "@tiptap/starter-kit";
import Link from "@tiptap/extension-link";
import { Markdown } from "tiptap-markdown";
import { useEffect, useRef } from "react";
import "../../styles/editor.css";

interface MarkdownEditorProps {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  minHeight?: number;
}

const MENU_BTN_BASE =
  "border-none rounded-sm px-2 py-1 cursor-pointer text-sm font-ui";

const Divider = () => (
  <span className="w-px bg-border-default mx-1 self-stretch" />
);

type MarkdownStorage = {
  markdown?: {
    getMarkdown: () => string;
  };
};

const MenuBar = ({ editor }: { editor: Editor | null }) => {
  if (!editor) {
    return null;
  }

  const btnClass = (isActive: boolean) =>
    `${MENU_BTN_BASE} ${
      isActive
        ? "bg-bg-hover text-text-primary font-semibold"
        : "bg-transparent text-text-secondary font-normal"
    }`;


  return (
    <div className="flex gap-1 p-2 border-b border-border-default bg-bg-secondary rounded-t-sm overflow-x-auto no-scrollbar whitespace-nowrap shrink-0">
      <button
        type="button"
        onClick={() => editor.chain().focus().toggleBold().run()}
        className={btnClass(editor.isActive("bold"))}
      >
        B
      </button>
      <button
        type="button"
        onClick={() => editor.chain().focus().toggleItalic().run()}
        className={btnClass(editor.isActive("italic"))}
      >
        I
      </button>
      <button
        type="button"
        onClick={() => editor.chain().focus().toggleStrike().run()}
        className={btnClass(editor.isActive("strike"))}
      >
        S
      </button>
      <Divider />
      <button
        type="button"
        onClick={() => editor.chain().focus().toggleHeading({ level: 1 }).run()}
        className={btnClass(editor.isActive("heading", { level: 1 }))}
      >
        H1
      </button>
      <button
        type="button"
        onClick={() => editor.chain().focus().toggleHeading({ level: 2 }).run()}
        className={btnClass(editor.isActive("heading", { level: 2 }))}
      >
        H2
      </button>
      <button
        type="button"
        onClick={() => editor.chain().focus().toggleHeading({ level: 3 }).run()}
        className={btnClass(editor.isActive("heading", { level: 3 }))}
      >
        H3
      </button>
      <Divider />
      <button
        type="button"
        onClick={() => editor.chain().focus().toggleBulletList().run()}
        className={btnClass(editor.isActive("bulletList"))}
      >
        UL
      </button>
      <button
        type="button"
        onClick={() => editor.chain().focus().toggleOrderedList().run()}
        className={btnClass(editor.isActive("orderedList"))}
      >
        OL
      </button>
      <button
        type="button"
        onClick={() => editor.chain().focus().toggleBlockquote().run()}
        className={btnClass(editor.isActive("blockquote"))}
      >
        Quote
      </button>
      <Divider />
      <button
        type="button"
        onClick={() => editor.chain().focus().toggleCodeBlock().run()}
        className={btnClass(editor.isActive("codeBlock"))}
      >
        Code
      </button>
      <button
        type="button"
        onClick={() => editor.chain().focus().setHorizontalRule().run()}
        className={btnClass(false)}
      >
        ---
      </button>
    </div>
  );
};

export function MarkdownEditor({ value, onChange, minHeight = 180 }: MarkdownEditorProps) {
  const internalValueRef = useRef(value);

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
      const md = (editor.storage as MarkdownStorage).markdown?.getMarkdown() ?? editor.getText();
      internalValueRef.current = md;
      onChange(md);
    },
    editorProps: {
      attributes: {
        class: "tiptap-editor-content",
        style: `min-height: ${minHeight}px; padding: var(--space-3); outline: none;`,
      },
    },
  });

  useEffect(() => {
    if (editor && value !== internalValueRef.current) {
      editor.commands.setContent(value);
      internalValueRef.current = value;
    }
  }, [value, editor]);

  return (
    <div className="border border-border-default rounded-sm flex flex-col bg-bg-primary overflow-hidden">
      <MenuBar editor={editor} />
      <div
        className="rd-markdown-container flex-1 overflow-y-auto cursor-text"
        onClick={() => editor?.commands.focus()}
      >
        <EditorContent editor={editor} />
      </div>
    </div>
  );
}
