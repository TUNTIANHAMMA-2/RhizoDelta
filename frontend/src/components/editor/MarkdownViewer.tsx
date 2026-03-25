import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import "../../styles/editor.css";

interface MarkdownViewerProps {
  content: string;
}

export function MarkdownViewer({ content }: MarkdownViewerProps) {
  if (!content) return null;

  return (
    <div className="rd-markdown-container read-only">
      <ReactMarkdown remarkPlugins={[remarkGfm]}>
        {content}
      </ReactMarkdown>
    </div>
  );
}
