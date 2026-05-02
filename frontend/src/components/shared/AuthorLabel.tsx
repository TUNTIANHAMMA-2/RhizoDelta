interface AuthorLabelProps {
  displayName?: string | null;
  username?: string | null;
  authorId?: string | null;
  className?: string;
}

/**
 * 解析作者展示名。优先级：display_name → username → "Anonymous"。
 *
 * <p>不再回退到 author_id —— 那是个 UUID，外露 UI 体验差，且未授权用户不应
 * 直接看到内部主键。前端只负责显示已经"对外友好"的字段。
 */
export function resolveAuthorName(
  displayName?: string | null,
  username?: string | null,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  _authorId?: string | null,
): string {
  return displayName ?? username ?? "Anonymous";
}

export function AuthorLabel({
  displayName,
  username,
  authorId,
  className,
}: AuthorLabelProps) {
  return (
    <span className={className}>{resolveAuthorName(displayName, username, authorId)}</span>
  );
}
