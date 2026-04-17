import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useGraphStore } from "../../stores/graphStore";
import { useUiStore } from "../../stores/uiStore";
import { fetchEmbedding } from "../../api/nodes";
import { searchSimilar } from "../../api/search";
import { loadGraphForRoot } from "../../lib/loadGraphForRoot";
import type { GraphNodeDTO, NodeLabel, SimilaritySearchResult } from "../../api/types";

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
  const requestFocusNode = useGraphStore((s) => s.requestFocusNode);
  const selectedNodeId = useGraphStore((s) => s.selectedNodeId);
  const rootNodeId = useGraphStore((s) => s.rootNodeId);
  const openDetailPanel = useUiStore((s) => s.openDetailPanel);

  // Vector search state
  const [similarResults, setSimilarResults] = useState<SimilaritySearchResult[]>([]);
  const [similarLoading, setSimilarLoading] = useState(false);
  const [similarError, setSimilarError] = useState<string | null>(null);
  const [showSimilar, setShowSimilar] = useState(false);

  // Check if selected node has embedding
  const selectedNode = selectedNodeId ? nodes.get(selectedNodeId) : undefined;
  const canSearchSimilar = selectedNode?.has_embedding === true;

  // Reset state whenever the palette opens
  useEffect(() => {
    if (isOpen) {
      setQuery("");
      setDebouncedQuery("");
      setActiveIndex(-1);
      activeIndexRef.current = -1;
      setShowSimilar(false);
      setSimilarResults([]);
      setSimilarError(null);
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
      requestFocusNode(nodeId);
      onClose();
    },
    [selectNode, openDetailPanel, requestFocusNode, onClose],
  );

  // ── Vector search ──────────────────────────────────────
  const handleFindSimilar = useCallback(async () => {
    if (!selectedNodeId) return;
    setSimilarLoading(true);
    setSimilarError(null);
    setSimilarResults([]);
    setShowSimilar(true);
    try {
      const { vector } = await fetchEmbedding(selectedNodeId);
      const results = await searchSimilar({ vector, top_k: 20 });
      setSimilarResults(results ?? []);
    } catch (err) {
      setSimilarError(err instanceof Error ? err.message : "搜索失败");
    } finally {
      setSimilarLoading(false);
    }
  }, [selectedNodeId]);

  const handleSelectSimilar = useCallback(
    async (result: SimilaritySearchResult) => {
      const targetNodeId = result.node_id;
      const existsLocally = nodes.has(targetNodeId);
      if (!existsLocally) {
        // Cross-Rhizone: load the target graph first
        const { loadLineage, loadChildren } = useGraphStore.getState();
        try {
          await loadGraphForRoot(targetNodeId, {
            loadLineage,
            loadChildren,
            onChildrenError: console.error,
          });
        } catch {
          // If loadGraphForRoot fails, still try to select
        }
      }
      selectNode(targetNodeId);
      openDetailPanel(targetNodeId);
      requestFocusNode(targetNodeId);
      onClose();
    },
    [nodes, selectNode, openDetailPanel, requestFocusNode, onClose],
  );

  // ── Keyboard navigation ──────────────────────────────────
  const onKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        if (showSimilar) {
          setShowSimilar(false);
          setSimilarResults([]);
          setSimilarError(null);
        } else {
          onClose();
        }
        return;
      }

      const currentList = showSimilar ? similarResults : results;

      if (e.key === "ArrowDown") {
        e.preventDefault();
        setActiveIndex((prev) => {
          const next = prev < currentList.length - 1 ? prev + 1 : 0;
          activeIndexRef.current = next;
          return next;
        });
        return;
      }

      if (e.key === "ArrowUp") {
        e.preventDefault();
        setActiveIndex((prev) => {
          const next = prev > 0 ? prev - 1 : currentList.length - 1;
          activeIndexRef.current = next;
          return next;
        });
        return;
      }

      if (e.key === "Enter" && activeIndexRef.current >= 0) {
        e.preventDefault();
        if (showSimilar) {
          const item = similarResults[activeIndexRef.current];
          if (item) handleSelectSimilar(item);
        } else {
          const node = results[activeIndexRef.current];
          if (node) handleSelect(node.node_id);
        }
      }
    },
    [results, similarResults, showSimilar, onClose, handleSelect, handleSelectSimilar],
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
        background: "rgba(26, 29, 27, 0.3)",
        backdropFilter: "blur(4px)",
        display: "flex",
        justifyContent: "center",
        alignItems: "flex-start",
        animation: "fade-in 150ms var(--ease-out)",
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
          marginTop: "18vh",
          background: "var(--color-bg-elevated)",
          borderRadius: "var(--radius-xl)",
          boxShadow: "var(--shadow-xl)",
          border: "1px solid var(--color-border-default)",
          overflow: "hidden",
          fontFamily: "var(--font-ui)",
          animation: "scale-in 200ms var(--ease-out)",
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
          {showSimilar ? (
            <>
              {similarLoading && (
                <div style={{ padding: "var(--space-4)", textAlign: "center", color: "var(--color-text-tertiary)", fontSize: "var(--font-size-sm)" }}>
                  搜索相似节点中...
                </div>
              )}
              {similarError && (
                <div style={{ padding: "var(--space-4)", textAlign: "center", color: "var(--color-danger)", fontSize: "var(--font-size-sm)" }}>
                  {similarError}
                </div>
              )}
              {!similarLoading && !similarError && similarResults.length === 0 && (
                <div style={{ padding: "var(--space-4)", textAlign: "center", color: "var(--color-text-tertiary)", fontSize: "var(--font-size-sm)" }}>
                  未找到相似节点
                </div>
              )}
              {similarResults.map((item, i) => {
                const isOtherRhizone = !nodes.has(item.node_id);
                return (
                  <div
                    key={item.node_id}
                    role="option"
                    aria-selected={i === activeIndex}
                    onClick={() => handleSelectSimilar(item)}
                    style={{
                      display: "flex",
                      alignItems: "center",
                      gap: "var(--space-3)",
                      padding: "var(--space-3) var(--space-4)",
                      cursor: "pointer",
                      background: i === activeIndex ? "var(--color-bg-hover, rgba(55,53,47,0.04))" : "transparent",
                      transition: "background 80ms ease",
                    }}
                    onMouseEnter={() => { setActiveIndex(i); activeIndexRef.current = i; }}
                  >
                    <div style={{ width: 3, alignSelf: "stretch", borderRadius: 2, background: LABEL_COLOR[item.label as NodeLabel] ?? "var(--color-text-tertiary)", flexShrink: 0 }} />
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ fontSize: "var(--font-size-sm)", color: "var(--color-text-primary)", fontFamily: "var(--font-content)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
                        {truncate(item.content ?? item.node_id, 80)}
                      </div>
                      <div style={{ display: "flex", alignItems: "center", gap: "var(--space-2)", marginTop: 2 }}>
                        <span style={{ display: "inline-block", padding: "1px 6px", borderRadius: "var(--radius-sm)", fontSize: 11, fontWeight: 500, color: LABEL_COLOR[item.label as NodeLabel] ?? "var(--color-text-secondary)", background: `color-mix(in srgb, ${LABEL_COLOR[item.label as NodeLabel] ?? "var(--color-text-secondary)"} 10%, transparent)`, lineHeight: 1.5 }}>
                          {LABEL_DISPLAY[item.label as NodeLabel] ?? item.label}
                        </span>
                        <span style={{ fontSize: 11, color: "var(--color-text-tertiary)" }}>
                          {(item.score * 100).toFixed(0)}% 相似
                        </span>
                        {isOtherRhizone && (
                          <span style={{ fontSize: 10, padding: "0 4px", borderRadius: "var(--radius-sm)", background: "var(--color-bg-tertiary)", color: "var(--color-text-tertiary)" }}>
                            跨话题
                          </span>
                        )}
                      </div>
                    </div>
                  </div>
                );
              })}
            </>
          ) : (
            <>
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
            </>
          )}
        </div>

        {/* ── Find Similar button ─────────────────── */}
        {canSearchSimilar && !showSimilar && (
          <div
            style={{
              borderTop: "1px solid var(--color-border-default)",
              padding: "var(--space-2) var(--space-4)",
            }}
          >
            <button
              type="button"
              onClick={handleFindSimilar}
              style={{
                width: "100%",
                border: "1px solid var(--color-border-default)",
                borderRadius: "var(--radius-sm)",
                padding: "var(--space-2)",
                background: "var(--color-bg-secondary)",
                color: "var(--color-text-secondary)",
                cursor: "pointer",
                fontFamily: "var(--font-ui)",
                fontSize: "var(--font-size-xs)",
                fontWeight: 500,
              }}
            >
              查找相似节点
            </button>
          </div>
        )}
        {showSimilar && (
          <div
            style={{
              borderTop: "1px solid var(--color-border-default)",
              padding: "var(--space-2) var(--space-4)",
            }}
          >
            <button
              type="button"
              onClick={() => { setShowSimilar(false); setSimilarResults([]); setSimilarError(null); }}
              style={{
                border: "none",
                background: "none",
                color: "var(--color-text-tertiary)",
                cursor: "pointer",
                fontFamily: "var(--font-ui)",
                fontSize: "var(--font-size-xs)",
                padding: "2px 0",
              }}
            >
              ← 返回文本搜索
            </button>
          </div>
        )}

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
  padding: "0 5px",
  border: "1px solid var(--color-border-default)",
  borderRadius: "var(--radius-sm)",
  fontSize: 10,
  fontFamily: "var(--font-mono)",
  lineHeight: "16px",
  background: "var(--color-bg-secondary)",
  boxShadow: "0 1px 0 var(--color-border-default)",
};
