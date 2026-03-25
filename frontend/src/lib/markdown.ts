export function stripMarkdown(md: string | null | undefined): string {
  if (!md) return "";

  return md
    // Code blocks (must be before inline code)
    .replace(/```[\s\S]*?```/gm, "[代码块]")
    // Horizontal rules (must be before bold/italic)
    .replace(/^[-*_]{3,}\s*$/gm, "")
    // Headers
    .replace(/^#{1,6}\s+/gm, "")
    // Images (must be before links!)
    .replace(/!\[([^\]]*)\]\([^)]+\)/g, "$1")
    // Links
    .replace(/\[([^\]]+)\]\([^)]+\)/g, "$1")
    // Bold, italic
    .replace(/(\*\*|__)(.*?)\1/g, "$2")
    .replace(/(\*|_)(.*?)\1/g, "$2")
    // Strikethrough
    .replace(/~~(.*?)~~/g, "$1")
    // Inline code
    .replace(/`(.*?)`/g, "$1")
    // Blockquotes
    .replace(/^>\s+/gm, "")
    // Lists
    .replace(/^[\s-]*[-*+]\s+/gm, "")
    .replace(/^[\s-]*\d+\.\s+/gm, "")
    // Remove extra newlines
    .trim();
}
