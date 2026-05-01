interface AuthorLabelProps {
  displayName?: string | null;
  username?: string | null;
  authorId?: string | null;
  className?: string;
}

export function resolveAuthorName(
  displayName?: string | null,
  username?: string | null,
  authorId?: string | null,
): string {
  return displayName ?? username ?? authorId ?? "Anonymous";
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
