import { useEffect, useState } from "react";
import clsx from "clsx";
import { metaLabel } from "../../lib/typography";

type RadiusMode = "round" | "sharp";

const STORAGE_KEY = "rd-radius-mode";

function readMode(): RadiusMode {
  if (typeof window === "undefined") return "round";
  const stored = window.localStorage.getItem(STORAGE_KEY);
  return stored === "sharp" ? "sharp" : "round";
}

function applyMode(mode: RadiusMode) {
  if (typeof document === "undefined") return;
  document.documentElement.setAttribute("data-radius", mode);
}

/**
 * 圆角档位切换 —— 让你能在「软角」与「直角」两套设计之间实时对比，
 * 找到符合产品气质的版本后再固化。
 *
 * 实现：拨动 <html data-radius="round|sharp">，tokens.css 里通过
 * 属性选择器把 --radius-* 系列变量批量切到 0，所有 rounded-* 工具类
 * 即时生效，无需重新加载或编译。
 *
 * 偏好持久化到 localStorage，刷新后保持你最后的选择。
 */
export function RadiusModeToggle() {
  const [mode, setMode] = useState<RadiusMode>(() => readMode());

  // 应用首屏挂载即同步一次，避免 FOUC（旧值在 head <script> 也可同步设置，
  // 这里作为兜底）。
  useEffect(() => {
    applyMode(mode);
    try {
      window.localStorage.setItem(STORAGE_KEY, mode);
    } catch {
      // ignore quota / disabled storage
    }
  }, [mode]);

  return (
    <div
      role="group"
      aria-label="Radius mode"
      className="inline-flex items-center gap-[2px] p-[2px] border border-border-default bg-bg-elevated/80 backdrop-blur-md"
      style={{ borderRadius: "var(--radius-control)" }}
    >
      {(["round", "sharp"] as const).map((m) => {
        const active = mode === m;
        return (
          <button
            key={m}
            type="button"
            onClick={() => setMode(m)}
            aria-pressed={active}
            className={clsx(
              "px-2 py-1 transition-colors",
              metaLabel,
              active
                ? "bg-accent-deep text-bg-primary"
                : "bg-transparent text-text-tertiary hover:text-text-secondary",
            )}
            style={{ borderRadius: "calc(var(--radius-control) - 2px)" }}
            title={m === "round" ? "圆角档：sm 6px / md 10px" : "直角档：所有矩形 0px"}
          >
            {m === "round" ? "⌒" : "⏐"}
            <span className="ml-1 hidden sm:inline">
              {m === "round" ? "Round" : "Sharp"}
            </span>
          </button>
        );
      })}
    </div>
  );
}
