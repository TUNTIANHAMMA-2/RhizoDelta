export function previewText(value: string | null | undefined, maxLength = 28) {
  const text = (value ?? "").replace(/\s+/g, " ").trim();
  if (text.length <= maxLength) return text || "…";
  return `${text.slice(0, maxLength - 1)}…`;
}

export function formatRelativeTime(value: string | null | undefined) {
  if (!value) return "";
  const date = new Date(value);
  const diffMs = date.getTime() - Date.now();
  const absMs = Math.abs(diffMs);
  const minute = 60_000;
  const hour = minute * 60;
  const day = hour * 24;
  const formatter = new Intl.RelativeTimeFormat("zh-CN", { numeric: "auto" });

  if (absMs < minute) return formatter.format(Math.round(diffMs / 1000), "second");
  if (absMs < hour) return formatter.format(Math.round(diffMs / minute), "minute");
  if (absMs < day) return formatter.format(Math.round(diffMs / hour), "hour");
  return formatter.format(Math.round(diffMs / day), "day");
}
