import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useGraphStore } from "../../stores/graphStore";
import { useUiStore } from "../../stores/uiStore";
import type { GraphNodeDTO, NodeLabel } from "../../api/types";

// ─── Props ───────────────────────────────────────────────────
export interface CommandPaletteProps {
  isOpen: boolean;
  onClose: () => void;
}

// ─── Label → colour mapping ──────────────────────────────────
const LABEL_COLOR: Record<NodeLabel, string> = {
  Human_Post: "var(--color-node-human)",
  AI_Consensus: "var(--color-node-consensus)",
  Result: "var(--color-node-result)",
};

const LABEL_DISPLAY: Record<NodeLabel, string> = {
  Human_Post: "人类帖子",
  AI_Consensus: "AI共识",
  Result: "结果",
};

// ─── Helpers ─────────────────────────────────────────────────
function truncate(text: string, max: number): string {
  if (text.length <= max) return text;
  return text.slice(0, max) + "...";
}

function formatDate(iso: string): string {
  try {
    const d = new Date(iso);
    const month = String(d.getMonth() + 1).padStart(2, "0");
    const day = String(d.getDate()).padStart(2, "0");
    const hours = String(d.getHours()).padStart(2, "0");
    const minutes = String(d.getMinutes()).padStart(2, "0");
    return `${month}-${day} ${hours}:${minutes}`;
  } catch {
    return iso;
  }
}

const MAX_RESULTS = 20;
const RECENT_LIMIT = 10;
const DEBOUNCE_MS = 150;

// ─── Component ───────────────────────────────────────────────
export function CommandPalette({ isOpen, onClose }: CommandPaletteProps) {
  const [query, setQuery] = useState("");
  const [debouncedQuery, setDebouncedQuery] = useState("");
  const inputRef = useRef<HTMLInputElement>(null);
  const debounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const activeIndexRef = useRef(-1);
  const [activeIndex, setActiveIndex] = useState(-1);
  const listRef = useRef<HTMLDivElement>(null);

  const nodes = useGraphStore((s) => s.nodes);
  const selectNode = useGraphStore((s) => s.selectNode);
  const openDetailPanel = useUiStore((s) => s.openDetailPanel);

  // Reset state whenever the palette opens
  useEffect(() => {
    if (isOpen) {
      setQuery("");
      setDebouncedQuery("");
      setActiveIndex(-1);
      activeIndexRef.current = -1;
      // Autofocus on next frame so the element is mounted
      requestAnimationFrame(() => inputRef.current?.focus());
    }
  }, [isOpen]);

  // Debounce the query string
  useEffect(() => {
    if (debounceTimer.current) clearTimeout(debounceTimer.current);
    debounceTimer.current = setTimeout(() => {
      setDebouncedQuery(query);
    }, DEBOUNCE_MS);
    return () => {
      if (debounceTimer.current) clearTimeout(debounceTimer.current);
    };
  }, [query]);

  // ── Search logic ─────────────────────────────────────────
  const results: GraphNodeDTO[] = useMemo(() => {
    const all = Array.from(nodes.values());

    if (!debouncedQuery.trim()) {
      // No query → show recently created nodes
      return all
        .sort((a, b) => b.created_at.localeCompare(a.created_at))
        .slice(0, RECENT_LIMIT);
    }

    const lowerQ = debouncedQuery.toLowerCase();
    const matched: GraphNodeDTO[] = [];

    for (const node of all) {
      if (matched.length >= MAX_RESULTS) break;
      const content = (node.content ?? "").toLowerCase();
      const summary = (node.summary_content ?? "").toLowerCase();
      if (content.includes(lowerQ) || summary.includes(lowerQ)) {
        matched.push(node);
      }
    }

    return matched;
  }, [nodes, debouncedQuery]);

  // Reset active index when results change
  useEffect(() => {
    setActiveIndex(-1);
    activeIndexRef.current = -1;
  }, [results]);

  // ── Select a result ──────────────────────────────────────
  const handleSelect = useCallback(
    (nodeId: string) => {
      selectNode(nodeId);
      openDetailPanel(nodeId);
      onClose();
    },
    [selectNode, openDetailPanel, onClose],
  );

  // ── Keyboard navigation ──────────────────────────────────
  const onKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        onClose();
        return;
      }

      if (e.key === "ArrowDown") {
        e.preventDefault();
        setActiveIndex((prev) => {
          const next = prev < results.length - 1 ? prev + 1 : 0;
          activeIndexRef.current = next;
          return next;
        });
        return;
      }

      if (e.key === "ArrowUp") {
        e.preventDefault();
        setActiveIndex((prev) => {
          const next = prev > 0 ? prev - 1 : results.length - 1;
          activeIndexRef.current = next;
          return next;
        });
        return;
      }

      if (e.key === "Enter" && activeIndexRef.current >= 0) {
        e.preventDefault();
        const node = results[activeIndexRef.current];
        if (node) handleSelect(node.node_id);
      }
    },
    [results, onClose, handleSelect],
  );

  // Scroll active item into view
  useEffect(() => {
    if (activeIndex < 0 || !listRef.current) return;
    const item = listRef.current.children[activeIndex] as HTMLElement | undefined;
    item?.scrollIntoView({ block: "nearest" });
  }, [activeIndex]);

  // ── Render nothing when closed ───────────────────────────
  if (!isOpen) return null;

  return (
    <div
      style={{
        position: "fixed",
        inset: 0,
        zIndex: 200,
        background: "rgba(55, 53, 47, 0.4)",
        display: "flex",
        justifyContent: "center",
        alignItems: "flex-start",
      }}
      onClick={onClose}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label="搜索节点"
        onClick={(e) => e.stopPropagation()}
        onKeyDown={onKeyDown}
        style={{
          maxWidth: 560,
          width: "90%",
          marginTop: "20vh",
          background: "var(--color-bg-primary)",
          borderRadius: "var(--radius-lg)",
          boxShadow: "var(--shadow-lg)",
          backdropFilter: "blur(12px)",
          overflow: "hidden",
          fontFamily: "var(--font-ui)",
        }}
      >
        {/* ── Search input ─────────────────────────── */}
        <input
          ref={inputRef}
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="搜索节点..."
          autoFocus
          style={{
            display: "block",
            width: "100%",
            border: "none",
            borderBottom: "1px solid var(--color-border-default)",
            outline: "none",
            background: "transparent",
            fontFamily: "var(--font-ui)",
            fontSize: "var(--font-size-md)",
            padding: "var(--space-4)",
            color: "var(--color-text-primary)",
            boxSizing: "border-box",
          }}
        />

        {/* ── Results list ─────────────────────────── */}
        <div
          ref={listRef}
          role="listbox"
          style={{
            maxHeight: 400,
            overflowY: "auto",
          }}
        >
          {results.length === 0 && debouncedQuery.trim() && (
            <div
              style={{
                padding: "var(--space-4)",
                color: "var(--color-text-tertiary)",
                fontSize: "var(--font-size-sm)",
                textAlign: "center",
              }}
            >
              没有匹配的节点
            </div>
          )}

          {results.length === 0 && !debouncedQuery.trim() && (
            <div
              style={{
                padding: "var(--space-4)",
                color: "var(--color-text-tertiary)",
                fontSize: "var(--font-size-sm)",
                textAlign: "center",
              }}
            >
              当前无节点数据
            </div>
          )}

          {results.map((node, i) => (
            <div
              key={node.node_id}
              role="option"
              aria-selected={i === activeIndex}
              onClick={() => handleSelect(node.node_id)}
              style={{
                display: "flex",
                alignItems: "center",
                gap: "var(--space-3)",
                padding: "var(--space-3) var(--space-4)",
                cursor: "pointer",
                background:
                  i === activeIndex
                    ? "var(--color-bg-hover, rgba(55,53,47,0.04))"
                    : "transparent",
                transition: "background 80ms ease",
              }}
              onMouseEnter={() => {
                setActiveIndex(i);
                activeIndexRef.current = i;
              }}
            >
              {/* Left color bar */}
              <div
                style={{
                  width: 3,
                  alignSelf: "stretch",
                  borderRadius: 2,
                  background: LABEL_COLOR[node.label] ?? "var(--color-text-tertiary)",
                  flexShrink: 0,
                }}
              />

              {/* Content */}
              <div style={{ flex: 1, minWidth: 0 }}>
                <div
                  style={{
                    fontSize: "var(--font-size-sm)",
                    color: "var(--color-text-primary)",
                    fontFamily: "var(--font-content)",
                    whiteSpace: "nowrap",
                    overflow: "hidden",
                    textOverflow: "ellipsis",
                  }}
                >
                  {truncate(node.content ?? node.summary_content ?? node.node_id, 80)}
                </div>

                <div
                  style={{
                    display: "flex",
                    alignItems: "center",
                    gap: "var(--space-2)",
                    marginTop: 2,
                  }}
                >
                  {/* Label badge */}
                  <span
                    style={{
                      display: "inline-block",
                      padding: "1px 6px",
                      borderRadius: "var(--radius-sm)",
                      fontSize: 11,
                      fontWeight: 500,
                      color: LABEL_COLOR[node.label] ?? "var(--color-text-secondary)",
                      background: `color-mix(in srgb, ${LABEL_COLOR[node.label] ?? "var(--color-text-secondary)"} 10%, transparent)`,
                      lineHeight: 1.5,
                    }}
                  >
                    {LABEL_DISPLAY[node.label] ?? node.label}
                  </span>

                  {/* Date */}
                  <span
                    style={{
                      fontSize: 11,
                      color: "var(--color-text-tertiary)",
                    }}
                  >
                    {formatDate(node.created_at)}
                  </span>
                </div>
              </div>
            </div>
          ))}
        </div>

        {/* ── Footer hint ──────────────────────────── */}
        <div
          style={{
            borderTop: "1px solid var(--color-border-default)",
            padding: "var(--space-2) var(--space-4)",
            display: "flex",
            alignItems: "center",
            gap: "var(--space-3)",
            fontSize: 11,
            color: "var(--color-text-tertiary)",
          }}
        >
          <span>
            <kbd style={kbdStyle}>↑↓</kbd> 导航
          </span>
          <span>
            <kbd style={kbdStyle}>↵</kbd> 选择
          </span>
          <span>
            <kbd style={kbdStyle}>esc</kbd> 关闭
          </span>
        </div>
      </div>
    </div>
  );
}

// Shared kbd style for the footer
const kbdStyle: React.CSSProperties = {
  display: "inline-block",
  padding: "0 4px",
  border: "1px solid var(--color-border-default)",
  borderRadius: "var(--radius-sm)",
  fontSize: 10,
  fontFamily: "var(--font-ui)",
  lineHeight: "16px",
  background: "var(--color-bg-secondary, #F1F0ED)",
};
