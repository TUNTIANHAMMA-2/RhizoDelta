import clsx from "clsx";
import { useMemo } from "react";
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

interface HomeMainColumnProps {
  onOpenSearch: () => void;
}

const SORT_OPTIONS: Array<{ key: HomeSortKey; en: string; zh: string }> = [
  { key: "latest", en: "Latest", zh: "最新" },
  { key: "quality", en: "Quality", zh: "质量" },
  { key: "active", en: "Active", zh: "活跃" },
];

function Hero({ onOpenSearch }: { onOpenSearch: () => void }) {
  const isMac =
    typeof navigator !== "undefined" &&
    /Mac|iPhone|iPad/.test(navigator.platform);
  const chord = isMac ? "⌘K" : "Ctrl+K";

  return (
    <div className="relative py-14 px-8 md:px-12 lg:px-16 border-b border-border-subtle bg-bg-elevated">
      <div className="max-w-[620px] mx-auto text-center space-y-6">
        <div
          className={clsx(
            metaLabel,
            "text-accent uppercase tracking-[0.28em]",
          )}
        >
          Rhizodelta · 观察台
        </div>
        <h1 className="font-content text-[44px] md:text-[52px] lg:text-[60px] leading-[1.02] tracking-[-0.03em] text-text-primary">
          Where threads
          <br />
          <em
            className="italic font-light text-accent"
            style={{ fontFeatureSettings: "'ss01'" }}
          >
            take root
          </em>
          <span className="text-text-primary">.</span>
        </h1>
        <p className="font-content italic text-text-secondary text-base md:text-lg leading-[1.55] max-w-lg mx-auto font-light">
          一处可并、可分、可反思的知识丛林 ——
          每一条观点都带出它的来路与去向。
        </p>

        <button
          type="button"
          onClick={onOpenSearch}
          className="mt-4 group relative w-full max-w-md mx-auto flex items-center gap-3 border border-border-default bg-bg-primary px-4 py-3 text-left hover:border-accent transition-colors"
        >
          <svg
            width="16"
            height="16"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
            className="text-text-tertiary group-hover:text-accent transition-colors"
            aria-hidden
          >
            <circle cx="11" cy="11" r="7" />
            <line x1="21" y1="21" x2="16.65" y2="16.65" />
          </svg>
          <span className="flex-1 font-content text-text-tertiary text-sm">
            搜索 rhizome、节点、作者…
          </span>
          <kbd
            className={clsx(
              metaLabel,
              "px-2 py-1 border border-border-default rounded-sm text-text-tertiary bg-bg-canvas",
            )}
          >
            {chord}
          </kbd>
        </button>
      </div>
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
      className="flex items-center gap-6 px-8 md:px-12 lg:px-16 py-4 border-b border-border-subtle bg-bg-elevated"
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
              "group relative pb-2 text-left transition-colors",
              active
                ? "text-text-primary"
                : "text-text-tertiary hover:text-text-secondary",
            )}
          >
            <span className={clsx("block", metaLabel)}>{opt.en}</span>
            <span
              className={clsx(
                "block font-content text-[15px] mt-0.5",
                active ? "italic" : "",
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
      <div className="ml-auto">
        <NewThreadButton />
      </div>
    </div>
  );
}

function NewThreadButton() {
  const openPostPanel = useUiStore((s) => s.openPostPanel);
  return (
    <button
      type="button"
      onClick={openPostPanel}
      className="group flex items-center gap-2 border border-text-primary bg-text-primary text-bg-primary px-4 py-2 hover:bg-accent hover:border-accent transition-colors"
    >
      <span className={clsx(metaLabel)}>New thread</span>
      <span className="font-content italic text-sm">发起</span>
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
      <p className="font-content italic text-text-secondary text-base max-w-md mb-6">
        先埋下第一粒种子 —— rhizomes 从一个想法开始。
      </p>
      <button
        type="button"
        onClick={openPostPanel}
        className="group flex items-center gap-3 border border-text-primary bg-text-primary text-bg-primary px-5 py-3 hover:bg-accent hover:border-accent transition-colors"
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

export function HomeMainColumn({ onOpenSearch }: HomeMainColumnProps) {
  const rhizomes = useGraphStore((s) => s.rhizomes);
  const activeNav = useHomeStore((s) => s.activeNav);
  const sortBy = useHomeStore((s) => s.sortBy);
  const userId = useAuthStore((s) => s.userId);

  const visible = useMemo(
    () => sortRhizomes(filterRhizomes(rhizomes, activeNav, userId), sortBy),
    [rhizomes, activeNav, userId, sortBy],
  );

  const emptyReason =
    rhizomes.length === 0
      ? "No rhizomes yet."
      : activeNav === "mine"
        ? "You haven't seeded any rhizomes yet."
        : activeNav === "recent"
          ? "No rhizomes in the last 24 hours."
          : "No rhizomes match this filter.";

  return (
    <section className="flex-1 min-w-0 flex flex-col">
      <Hero onOpenSearch={onOpenSearch} />
      <SortTabs />
      <div className="flex-1 bg-bg-elevated">
        {visible.length === 0 ? (
          <EmptyState reason={emptyReason} />
        ) : (
          visible.map((node) => (
            <RhizomeCard key={node.node_id} node={node} />
          ))
        )}
      </div>
    </section>
  );
}
