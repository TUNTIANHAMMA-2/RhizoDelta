import clsx from "clsx";
import { useEffect, useMemo } from "react";
import { useNavigate } from "react-router-dom";
import { useAuthStore } from "../../stores/authStore";
import { useGraphStore } from "../../stores/graphStore";
import {
  qualityBandOf,
  useHomeStore,
  type HomeNavKey,
  type QualityBand,
} from "../../stores/homeStore";
import { homeSidebarLabels } from "../../i18n/labels";
import { metaLabel } from "../../lib/typography";
import { WordMark } from "../brand/WordMark";

interface NavItem {
  key: HomeNavKey;
  label: string;
  sub?: string;
  disabled?: boolean;
}

function SectionHeading({ children }: { children: string }) {
  return (
    <div className="px-5 mt-9 mb-3 flex items-center gap-3">
      <span
        className={clsx(
          metaLabel,
          "text-text-tertiary/80 tracking-[0.05em] text-[12px] whitespace-nowrap",
        )}
      >
        {children}
      </span>
      <span
        className="flex-1 h-px bg-border-default/40"
        aria-hidden
      />
    </div>
  );
}

function NavRow({
  item,
  active,
  count,
  indicatorClass,
  onClick,
}: {
  item: NavItem;
  active: boolean;
  count?: number;
  indicatorClass?: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      disabled={item.disabled}
      onClick={onClick}
      aria-current={active ? "page" : undefined}
      className={clsx(
        "group w-full flex items-center justify-between px-5 py-2.5 text-left transition-colors",
        active
          ? "bg-bg-selected/80 text-text-primary"
          : "text-text-secondary hover:bg-bg-hover/50 hover:text-text-primary",
        item.disabled && "opacity-40 cursor-not-allowed",
      )}
    >
      <span className="flex items-center gap-3 min-w-0">
        <span
          className={clsx(
            "inline-block w-[3px] h-5 rounded-pill transition-colors",
            active
              ? "bg-accent"
              : (indicatorClass ?? "bg-transparent group-hover:bg-accent/30"),
          )}
          aria-hidden
        />
        <span className="font-ui text-[14.5px] truncate">{item.label}</span>
      </span>
      {typeof count === "number" && (
        <span
          className={clsx(
            metaLabel,
            "tabular-nums text-text-tertiary text-[11.5px]",
            active && "text-accent",
          )}
        >
          {count}
        </span>
      )}
    </button>
  );
}

export function HomeSidebar() {
  const navigate = useNavigate();
  const activeNav = useHomeStore((s) => s.activeNav);
  const setActiveNav = useHomeStore((s) => s.setActiveNav);
  const followingTargetIds = useHomeStore((s) => s.followingTargetIds);
  const loadFollowing = useHomeStore((s) => s.loadFollowing);
  const rhizomes = useGraphStore((s) => s.rhizomes);
  const userId = useAuthStore((s) => s.userId);

  // 登录后第一次拉一次关注列表，让侧栏 Following 计数与右栏过滤都拿到真实集合。
  useEffect(() => {
    if (userId) {
      loadFollowing();
    }
  }, [userId, loadFollowing]);

  const { total, mine, recent, following, qualityCounts } = useMemo(() => {
    // Clock snapshot for the 24h "recent" window; recomputed when rhizomes change.
    // eslint-disable-next-line react-hooks/purity
    const now = Date.now();
    const RECENT_MS = 24 * 60 * 60 * 1000;
    const q: Record<QualityBand, number> = {
      top: 0,
      good: 0,
      basic: 0,
      unrated: 0,
    };
    let mineCount = 0;
    let recentCount = 0;
    let followingCount = 0;
    for (const r of rhizomes) {
      q[qualityBandOf(r.quality_overall)]++;
      if (userId && r.author_id === userId) mineCount++;
      if (now - Date.parse(r.created_at) < RECENT_MS) recentCount++;
      if (
        followingTargetIds.has(r.node_id) ||
        (r.author_id != null && followingTargetIds.has(r.author_id))
      ) {
        followingCount++;
      }
    }
    return {
      total: rhizomes.length,
      mine: userId ? mineCount : 0,
      recent: recentCount,
      following: followingCount,
      qualityCounts: q,
    };
  }, [rhizomes, userId, followingTargetIds]);

  const streams: NavItem[] = [
    { key: "all", label: homeSidebarLabels.streams.all },
    {
      key: "following",
      label: homeSidebarLabels.streams.following,
      disabled: !userId,
    },
    { key: "mine", label: homeSidebarLabels.streams.mine, disabled: !userId },
    { key: "recent", label: homeSidebarLabels.streams.recent },
  ];

  const qualityBands: {
    key: HomeNavKey;
    label: string;
    count: number;
    dotClass: string;
  }[] = [
    {
      key: "quality:top",
      label: homeSidebarLabels.quality.top,
      count: qualityCounts.top,
      dotClass: "bg-accent",
    },
    {
      key: "quality:good",
      label: homeSidebarLabels.quality.good,
      count: qualityCounts.good,
      dotClass: "bg-accent/60",
    },
    {
      key: "quality:basic",
      label: homeSidebarLabels.quality.basic,
      count: qualityCounts.basic,
      dotClass: "bg-text-tertiary/60",
    },
    {
      key: "quality:unrated",
      label: homeSidebarLabels.quality.unrated,
      count: qualityCounts.unrated,
      dotClass: "bg-text-tertiary/25",
    },
  ];

  return (
    <aside
      className="flex w-full md:w-[272px] md:shrink-0 max-h-[78vh] md:h-screen md:max-h-none md:sticky md:top-0 flex-col bg-bg-parchment md:border-r border-border-default/70 font-ui overflow-y-auto"
      aria-label={homeSidebarLabels.ariaLabel}
    >
      {/* Brand header */}
      <div className="px-5 pt-6 pb-6 border-b border-border-default/50">
        <WordMark className="block text-[26px] leading-none tracking-[-0.02em]" />
        <div className="font-content text-text-secondary text-[14px] mt-2">
          {homeSidebarLabels.tagline}
        </div>
      </div>

      <SectionHeading>{homeSidebarLabels.sections.streams}</SectionHeading>
      <nav className="flex flex-col">
        {streams.map((item) => (
          <NavRow
            key={item.key}
            item={item}
            active={activeNav === item.key}
            count={
              item.key === "all"
                ? total
                : item.key === "mine"
                  ? mine
                  : item.key === "following"
                    ? following
                    : recent
            }
            onClick={() => setActiveNav(item.key)}
          />
        ))}
      </nav>

      <SectionHeading>{homeSidebarLabels.sections.quality}</SectionHeading>
      <nav className="flex flex-col">
        {qualityBands.map((item) => (
          <NavRow
            key={item.key}
            item={{ key: item.key, label: item.label }}
            active={activeNav === item.key}
            count={item.count}
            indicatorClass={item.dotClass}
            onClick={() => setActiveNav(item.key)}
          />
        ))}
      </nav>

      <SectionHeading>{homeSidebarLabels.sections.resources}</SectionHeading>
      <nav className="flex flex-col pb-6">
        <button
          type="button"
          onClick={() => navigate("/workspace")}
          className="group w-full flex items-center gap-3 px-5 py-2.5 text-left text-text-secondary hover:bg-bg-hover/50 hover:text-text-primary transition-colors"
        >
          <span
            className="inline-block w-[3px] h-5 rounded-pill bg-transparent group-hover:bg-accent/30"
            aria-hidden
          />
          <span className="font-ui text-[14.5px]">
            {homeSidebarLabels.resources.graph}
          </span>
          <span
            className={clsx(
              metaLabel,
              "ml-auto text-text-tertiary text-[11.5px]",
            )}
          >
            ↗
          </span>
        </button>
        <button
          type="button"
          onClick={() => navigate("/settings")}
          className="group w-full flex items-center gap-3 px-5 py-2.5 text-left text-text-secondary hover:bg-bg-hover/50 hover:text-text-primary transition-colors"
        >
          <span
            className="inline-block w-[3px] h-5 rounded-pill bg-transparent group-hover:bg-accent/30"
            aria-hidden
          />
          <span className="font-ui text-[14.5px]">
            {homeSidebarLabels.resources.settings}
          </span>
          <span
            className={clsx(
              metaLabel,
              "ml-auto text-text-tertiary text-[11.5px]",
            )}
          >
            ↗
          </span>
        </button>
      </nav>

      <div className="mt-auto px-5 py-6 border-t border-border-default/50 space-y-3">
        <p className="font-content text-[13px] leading-[1.65] text-text-secondary/90">
          {homeSidebarLabels.footerPoem[0]}
          <br />
          {homeSidebarLabels.footerPoem[1]}
        </p>
      </div>
    </aside>
  );
}
