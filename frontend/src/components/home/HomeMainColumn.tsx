import clsx from "clsx";
import { useEffect, useMemo } from "react";
import { useAuthStore } from "../../stores/authStore";
import { useGraphStore } from "../../stores/graphStore";
import {
  filterRhizomes,
  sortRhizomes,
  useHomeStore,
  type HomeSortKey,
} from "../../stores/homeStore";
import { useUiStore } from "../../stores/uiStore";
import { metaLabel } from "../../lib/typography";
import { RhizomeCard } from "./RhizomeCard";
import { Header } from "../chrome/Header";

const SORT_OPTIONS: Array<{ key: HomeSortKey; en: string; zh: string }> = [
  { key: "latest", en: "Latest", zh: "最新" },
  { key: "quality", en: "Quality", zh: "质量" },
  { key: "active", en: "Active", zh: "活跃" },
  { key: "for_you", en: "For You", zh: "为你推荐" },
];

function CompactSearch() {
  const isMac = typeof navigator !== "undefined" && /Mac|iPhone|iPad/.test(navigator.platform);
  const chord = isMac ? "⌘K" : "Ctrl+K";
  const openCommandPalette = useUiStore((s) => s.openCommandPalette);

  return (
    <div className="w-[90%] max-w-[800px] mx-auto mt-2 mb-4 flex flex-col md:flex-row items-center gap-4 animate-fade-in animate-slide-up [animation-delay:150ms] [animation-fill-mode:both]">
      {/* 纯文本非胶囊 Logo，并在移动端附带轻度呼吸动效；桌面端首页不显示 Logo */}
      <div className="md:hidden shrink-0 flex items-center font-content font-normal text-text-primary text-xl tracking-[-0.02em] animate-[pulse_2s_ease-in-out_infinite]">
        RhizoDelt
        <span className="text-accent text-xl ml-[2px]">△</span>
      </div>

      {/* 搜索框 */}
      <button
        type="button"
        onClick={openCommandPalette}
        className="group flex-1 w-full flex items-center gap-3 border border-border-default bg-bg-primary px-4 py-2.5 text-left hover:border-accent hover:shadow-sm transition-all rounded-pill"
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="text-text-tertiary group-hover:text-accent transition-colors" aria-hidden>
          <circle cx="11" cy="11" r="7" />
          <line x1="21" y1="21" x2="16.65" y2="16.65" />
        </svg>
        <span className="flex-1 font-content text-text-tertiary text-sm truncate">搜索 rhizome、节点、作者…</span>
        <kbd className={clsx(metaLabel, "px-2 py-0.5 border border-border-default rounded-sm text-text-tertiary bg-bg-canvas hidden sm:inline-block")}>
          {chord}
        </kbd>
      </button>

      {/* 桌面端：发起按钮紧靠搜索框右侧 */}
      <NewThreadButton />
    </div>
  );
}

function SortTabs() {
  const sortBy = useHomeStore((s) => s.sortBy);
  const setSortBy = useHomeStore((s) => s.setSortBy);

  return (
    <div
      role="tablist"
      aria-label="Sort rhizomes"
      className="flex items-center gap-6 md:gap-8 px-5 md:px-12 lg:px-16 py-3 border-b border-border-subtle bg-bg-elevated overflow-x-auto no-scrollbar scroll-smooth whitespace-nowrap snap-x snap-mandatory"
    >
      {SORT_OPTIONS.map((opt) => {
        const active = sortBy === opt.key;
        return (
          <button
            key={opt.key}
            type="button"
            role="tab"
            aria-selected={active}
            onClick={() => setSortBy(opt.key)}
            className={clsx(
              "group relative pb-2 text-left transition-colors shrink-0 snap-start",
              active
                ? "text-text-primary"
                : "text-text-tertiary hover:text-text-secondary",
            )}
          >
            <span className={clsx("block", metaLabel)}>{opt.en}</span>
            <span
              className={clsx(
                "block font-content text-[15px] mt-0.5",
                active ? "text-accent" : "",
              )}
            >
              {opt.zh}
            </span>
            {active && (
              <span
                className="absolute left-0 right-0 -bottom-px h-[2px] bg-accent"
                aria-hidden
              />
            )}
          </button>
        );
      })}
    </div>
  );
}

function NewThreadButton({ isFab = false }: { isFab?: boolean }) {
  const openPostPanel = useUiStore((s) => s.openPostPanel);
  
  if (isFab) {
    return (
      <button
        type="button"
        onClick={openPostPanel}
        className="md:hidden fixed right-5 bottom-8 z-[90] flex items-center justify-center w-14 h-14 rounded-full bg-accent-deep text-bg-primary shadow-lg hover:scale-105 active:scale-95 transition-all"
        aria-label="New thread"
      >
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <line x1="12" y1="5" x2="12" y2="19" />
          <line x1="5" y1="12" x2="19" y2="12" />
        </svg>
      </button>
    );
  }

  return (
    <button
      type="button"
      onClick={openPostPanel}
      className="hidden md:flex shrink-0 group items-center justify-center gap-2 border border-accent-deep bg-accent-deep text-bg-primary px-5 py-2.5 rounded-pill hover:bg-accent hover:border-accent transition-colors shadow-sm"
    >
      <span className={clsx(metaLabel, "tracking-[0.06em]")}>New thread</span>
      <span
        aria-hidden
        className="inline-block transition-transform duration-300 group-hover:translate-x-0.5"
      >
        ↗
      </span>
    </button>
  );
}

function EmptyState({ reason }: { reason: string }) {
  const openPostPanel = useUiStore((s) => s.openPostPanel);
  return (
    <div className="flex flex-col items-center justify-center py-24 px-8 text-center">
      <div
        className={clsx(
          metaLabel,
          "text-text-tertiary uppercase tracking-[0.18em] mb-3",
        )}
      >
        empty
      </div>
      <h2 className="font-content text-[28px] leading-[1.1] text-text-primary mb-3">
        {reason}
      </h2>
      <p className="font-content text-text-secondary text-[15px] max-w-md mb-6">
        先埋下第一粒种子 —— rhizomes 从一个想法开始。
      </p>
      <button
        type="button"
        onClick={openPostPanel}
        className="group flex items-center gap-3 border border-accent-deep bg-accent-deep text-bg-primary px-5 py-3 hover:bg-accent hover:border-accent transition-colors"
      >
        <span className={clsx(metaLabel)}>Plant a rhizome</span>
        <span
          aria-hidden
          className="inline-block transition-transform duration-300 group-hover:translate-x-0.5"
        >
          ↗
        </span>
      </button>
    </div>
  );
}

export function HomeMainColumn() {
  const rhizomes = useGraphStore((s) => s.rhizomes);
  const activeNav = useHomeStore((s) => s.activeNav);
  const sortBy = useHomeStore((s) => s.sortBy);
  const userId = useAuthStore((s) => s.userId);
  const feedItems = useHomeStore((s) => s.feedItems);
  const feedError = useHomeStore((s) => s.feedError);
  const loadFeed = useHomeStore((s) => s.loadFeed);
  const followingTargetIds = useHomeStore((s) => s.followingTargetIds);
  const loadFollowing = useHomeStore((s) => s.loadFollowing);

  useEffect(() => {
    if (sortBy === "for_you" && userId) {
      loadFeed();
    }
  }, [sortBy, userId, loadFeed]);

  useEffect(() => {
    if (activeNav === "following" && userId) {
      loadFollowing();
    }
  }, [activeNav, userId, loadFollowing]);

  const visible = useMemo(() => {
    if (sortBy === "for_you" && userId) {
      // For You 直接消费 feed 接口的结果，仍然走一遍本地排序兜底。
      return sortRhizomes(feedItems, sortBy);
    }
    return sortRhizomes(
      filterRhizomes(rhizomes, activeNav, userId, followingTargetIds),
      sortBy,
    );
  }, [rhizomes, activeNav, userId, sortBy, feedItems, followingTargetIds]);

  const emptyReason =
    sortBy === "for_you" && feedError
      ? `Feed unavailable: ${feedError}`
      : sortBy === "for_you"
      ? "Your personalized feed is empty — follow topics or people to fill it."
      : rhizomes.length === 0
      ? "No rhizomes yet."
      : activeNav === "mine"
        ? "You haven't seeded any rhizomes yet."
        : activeNav === "following"
          ? "Nothing from the people / topics you follow yet."
          : activeNav === "recent"
            ? "No rhizomes in the last 24 hours."
            : "No rhizomes match this filter.";

  return (
    <section className="flex-1 min-w-0 flex flex-col pt-0 md:pt-2 relative">
      <div className="md:hidden relative z-[100] animate-fade-in animate-slide-down">
        <Header hideLogo embedded />
      </div>
      <CompactSearch />
      <div className="animate-fade-in [animation-delay:300ms] [animation-fill-mode:both]">
        <SortTabs />
      </div>
      <div className="flex-1 bg-bg-elevated">
        {visible.length === 0 ? (
          <EmptyState reason={emptyReason} />
        ) : (
          visible.map((node, i) => (
            <div
              key={node.node_id}
              className={i < 8 ? "animate-fade-in animate-slide-up [animation-fill-mode:both]" : ""}
              style={i < 8 ? { animationDelay: `${300 + i * 50}ms` } : undefined}
            >
              <RhizomeCard node={node} />
            </div>
          ))
        )}
      </div>
      
      {/* 移动端悬浮发起按钮 */}
      <NewThreadButton isFab />
    </section>
  );
}
