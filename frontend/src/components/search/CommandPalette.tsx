import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import clsx from "clsx";
import { useGraphStore } from "../../stores/graphStore";
import { useUiStore } from "../../stores/uiStore";
import { fetchEmbedding } from "../../api/nodes";
import { searchSimilar } from "../../api/search";
import { loadGraphForRoot } from "../../lib/loadGraphForRoot";
import type { GraphNodeDTO, NodeLabel, SimilaritySearchResult } from "../../api/types";

export interface CommandPaletteProps {
  isOpen: boolean;
  onClose: () => void;
}

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

const KBD_CLASS =
  "inline-block px-[5px] border border-border-default rounded-sm text-[10px] font-mono leading-4 bg-bg-secondary shadow-[0_1px_0_var(--color-border-default)]";

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
  const openDetailPanel = useUiStore((s) => s.openDetailPanel);

  const [similarResults, setSimilarResults] = useState<SimilaritySearchResult[]>([]);
  const [similarLoading, setSimilarLoading] = useState(false);
  const [similarError, setSimilarError] = useState<string | null>(null);
  const [showSimilar, setShowSimilar] = useState(false);

  const selectedNode = selectedNodeId ? nodes.get(selectedNodeId) : undefined;
  const canSearchSimilar = selectedNode?.has_embedding === true;

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

  useEffect(() => {
    if (debounceTimer.current) clearTimeout(debounceTimer.current);
    debounceTimer.current = setTimeout(() => {
      setDebouncedQuery(query);
    }, DEBOUNCE_MS);
    return () => {
      if (debounceTimer.current) clearTimeout(debounceTimer.current);
    };
  }, [query]);

  const results: GraphNodeDTO[] = useMemo(() => {
    const all = Array.from(nodes.values());

    if (!debouncedQuery.trim()) {
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

  useEffect(() => {
    setActiveIndex(-1);
    activeIndexRef.current = -1;
  }, [results]);

  const handleSelect = useCallback(
    (nodeId: string) => {
      selectNode(nodeId);
      openDetailPanel(nodeId);
      requestFocusNode(nodeId);
      onClose();
    },
    [selectNode, openDetailPanel, requestFocusNode, onClose],
  );

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
        const { loadLineage, loadChildren } = useGraphStore.getState();
        try {
          await loadGraphForRoot(targetNodeId, {
            loadLineage,
            loadChildren,
            onChildrenError: console.error,
          });
        } catch {
          // swallow — still try to select
        }
      }
      selectNode(targetNodeId);
      openDetailPanel(targetNodeId);
      requestFocusNode(targetNodeId);
      onClose();
    },
    [nodes, selectNode, openDetailPanel, requestFocusNode, onClose],
  );

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

  useEffect(() => {
    if (activeIndex < 0 || !listRef.current) return;
    const item = listRef.current.children[activeIndex] as HTMLElement | undefined;
    item?.scrollIntoView({ block: "nearest" });
  }, [activeIndex]);

  if (!isOpen) return null;

  return (
    <div
      className="fixed inset-0 z-[200] bg-[rgba(26,29,27,0.3)] backdrop-blur-[4px] flex justify-center items-start animate-fade-in"
      onClick={onClose}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label="搜索节点"
        onClick={(e) => e.stopPropagation()}
        onKeyDown={onKeyDown}
        className="max-w-[560px] w-[90%] mt-[18vh] bg-bg-elevated rounded-xl shadow-xl border border-border-default overflow-hidden font-ui animate-scale-in"
      >
        {/* Search input */}
        <input
          ref={inputRef}
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="搜索节点..."
          autoFocus
          className="block w-full border-none border-b border-border-default outline-none bg-transparent font-ui text-md p-4 text-text-primary box-border"
        />

        {/* Results list */}
        <div ref={listRef} role="listbox" className="max-h-[400px] overflow-y-auto">
          {showSimilar ? (
            <>
              {similarLoading && (
                <div className="p-4 text-center text-text-tertiary text-sm">搜索相似节点中...</div>
              )}
              {similarError && (
                <div className="p-4 text-center text-danger text-sm">{similarError}</div>
              )}
              {!similarLoading && !similarError && similarResults.length === 0 && (
                <div className="p-4 text-center text-text-tertiary text-sm">未找到相似节点</div>
              )}
              {similarResults.map((item, i) => {
                const isOtherRhizone = !nodes.has(item.node_id);
                const labelColor = LABEL_COLOR[item.label as NodeLabel] ?? "var(--color-text-tertiary)";
                return (
                  <div
                    key={item.node_id}
                    role="option"
                    aria-selected={i === activeIndex}
                    onClick={() => handleSelectSimilar(item)}
                    onMouseEnter={() => { setActiveIndex(i); activeIndexRef.current = i; }}
                    className={clsx(
                      "flex items-center gap-3 px-4 py-3 cursor-pointer transition-[background] duration-[80ms]",
                      i === activeIndex ? "bg-bg-hover" : "bg-transparent",
                    )}
                  >
                    <div
                      className="w-[3px] self-stretch rounded-[2px] shrink-0"
                      style={{ background: labelColor }}
                    />
                    <div className="flex-1 min-w-0">
                      <div className="text-sm text-text-primary font-content whitespace-nowrap overflow-hidden text-ellipsis">
                        {truncate(item.content ?? item.node_id, 80)}
                      </div>
                      <div className="flex items-center gap-2 mt-[2px]">
                        <span
                          className="inline-block px-[6px] py-[1px] rounded-sm text-[11px] font-medium leading-[1.5]"
                          style={{
                            color: labelColor,
                            background: `color-mix(in srgb, ${labelColor} 10%, transparent)`,
                          }}
                        >
                          {LABEL_DISPLAY[item.label as NodeLabel] ?? item.label}
                        </span>
                        <span className="text-[11px] text-text-tertiary">
                          {(item.score * 100).toFixed(0)}% 相似
                        </span>
                        {isOtherRhizone && (
                          <span className="text-[10px] px-1 rounded-sm bg-bg-hover text-text-tertiary">
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
                <div className="p-4 text-text-tertiary text-sm text-center">
                  没有匹配的节点
                </div>
              )}

              {results.length === 0 && !debouncedQuery.trim() && (
                <div className="p-4 text-text-tertiary text-sm text-center">
                  当前无节点数据
                </div>
              )}

              {results.map((node, i) => {
                const labelColor = LABEL_COLOR[node.label] ?? "var(--color-text-tertiary)";
                return (
                  <div
                    key={node.node_id}
                    role="option"
                    aria-selected={i === activeIndex}
                    onClick={() => handleSelect(node.node_id)}
                    onMouseEnter={() => {
                      setActiveIndex(i);
                      activeIndexRef.current = i;
                    }}
                    className={clsx(
                      "flex items-center gap-3 px-4 py-3 cursor-pointer transition-[background] duration-[80ms]",
                      i === activeIndex ? "bg-bg-hover" : "bg-transparent",
                    )}
                  >
                    <div
                      className="w-[3px] self-stretch rounded-[2px] shrink-0"
                      style={{ background: labelColor }}
                    />
                    <div className="flex-1 min-w-0">
                      <div className="text-sm text-text-primary font-content whitespace-nowrap overflow-hidden text-ellipsis">
                        {truncate(node.content ?? node.summary_content ?? node.node_id, 80)}
                      </div>
                      <div className="flex items-center gap-2 mt-[2px]">
                        <span
                          className="inline-block px-[6px] py-[1px] rounded-sm text-[11px] font-medium leading-[1.5]"
                          style={{
                            color: labelColor,
                            background: `color-mix(in srgb, ${labelColor} 10%, transparent)`,
                          }}
                        >
                          {LABEL_DISPLAY[node.label] ?? node.label}
                        </span>
                        <span className="text-[11px] text-text-tertiary">
                          {formatDate(node.created_at)}
                        </span>
                      </div>
                    </div>
                  </div>
                );
              })}
            </>
          )}
        </div>

        {canSearchSimilar && !showSimilar && (
          <div className="border-t border-border-default px-4 py-2">
            <button
              type="button"
              onClick={handleFindSimilar}
              className="w-full border border-border-default rounded-sm p-2 bg-bg-secondary text-text-secondary cursor-pointer font-ui text-xs font-medium"
            >
              查找相似节点
            </button>
          </div>
        )}
        {showSimilar && (
          <div className="border-t border-border-default px-4 py-2">
            <button
              type="button"
              onClick={() => { setShowSimilar(false); setSimilarResults([]); setSimilarError(null); }}
              className="border-none bg-transparent text-text-tertiary cursor-pointer font-ui text-xs py-[2px]"
            >
              ← 返回文本搜索
            </button>
          </div>
        )}

        <div className="border-t border-border-default px-4 py-2 flex items-center gap-3 text-[11px] text-text-tertiary">
          <span>
            <kbd className={KBD_CLASS}>↑↓</kbd> 导航
          </span>
          <span>
            <kbd className={KBD_CLASS}>↵</kbd> 选择
          </span>
          <span>
            <kbd className={KBD_CLASS}>esc</kbd> 关闭
          </span>
        </div>
      </div>
    </div>
  );
}
