export const metaLabel =
  "font-mono text-[11px] font-medium tracking-[0.08em]";

const MINUTE = 60 * 1000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const WEEK = 7 * DAY;

export function relativeTime(isoOrDate: string | Date, now = Date.now()): string {
  const then = typeof isoOrDate === "string"
    ? Date.parse(isoOrDate)
    : isoOrDate.getTime();
  if (Number.isNaN(then)) return "";
  const diff = now - then;
  if (diff < 0) return "just now";
  if (diff < MINUTE) return "just now";
  if (diff < HOUR) return `${Math.floor(diff / MINUTE)}m`;
  if (diff < DAY) return `${Math.floor(diff / HOUR)}h`;
  if (diff < WEEK) return `${Math.floor(diff / DAY)}d`;
  return new Date(then).toISOString().slice(0, 10);
}
