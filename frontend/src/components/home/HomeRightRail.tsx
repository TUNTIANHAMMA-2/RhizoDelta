import clsx from "clsx";
import { useMemo } from "react";
import { useNavigate } from "react-router-dom";
import { useAuthStore } from "../../stores/authStore";
import { useGraphStore } from "../../stores/graphStore";
import {
  qualityBandOf,
  useHomeStore,
  type HomeNavKey,
  type QualityBand,
} from "../../stores/homeStore";
import { useNotificationStore } from "../../stores/notificationStore";
import type { GraphNodeDTO } from "../../api/types";
import { metaLabel, relativeTime } from "../../lib/typography";
import { stripMarkdown } from "../../lib/markdown";
import { HeaderActions } from "../chrome/Header";

function SectionCard({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <section className="border-b border-border-subtle pb-5">
      <h3
        className={clsx(
          metaLabel,
          "px-4 pt-4 pb-3 uppercase tracking-[0.14em] text-text-tertiary",
        )}
      >
        {title}
      </h3>
      <div className="px-4">{children}</div>
    </section>
  );
}

function QualityTop() {
  const navigate = useNavigate();
  const rhizomes = useGraphStore((s) => s.rhizomes);
  const top5 = [...rhizomes]
    .filter((n) => typeof n.quality_overall === "number")
    .sort((a, b) => (b.quality_overall ?? 0) - (a.quality_overall ?? 0))
    .slice(0, 5);

  if (top5.length === 0) {
    return (
      <div className="text-text-tertiary text-[13px] leading-relaxed">
        还没有被评分的话题
      </div>
    );
  }

  return (
    <ol className="space-y-2.5">
      {top5.map((n, idx) => {
        const title =
          stripMarkdown(n.content ?? n.summary_content).split("\n")[0] ||
          "Untitled";
        return (
          <li key={n.node_id}>
            <button
              type="button"
              onClick={() => navigate(`/workspace/${n.node_id}`)}
              className="w-full text-left flex items-start gap-2.5 group"
            >
              <span
                className={clsx(
                  metaLabel,
                  "tabular-nums text-accent shrink-0 pt-[2px] min-w-[18px]",
                )}
              >
                {String(idx + 1).padStart(2, "0")}
              </span>
              <span className="flex-1 min-w-0">
                <span className="block font-content text-[14px] leading-[1.4] text-text-primary truncate group-hover:text-accent transition-colors">
                  {title}
                </span>
                <span
                  className={clsx(
                    metaLabel,
                    "text-text-tertiary tabular-nums",
                  )}
                >
                  Q {(n.quality_overall ?? 0).toFixed(2)}
                </span>
              </span>
            </button>
          </li>
        );
      })}
    </ol>
  );
}

function QualityDistribution() {
  const rhizomes = useGraphStore((s) => s.rhizomes);
  const activeNav = useHomeStore((s) => s.activeNav);
  const setActiveNav = useHomeStore((s) => s.setActiveNav);
  const total = rhizomes.length;

  const counts: Record<QualityBand, number> = {
    top: 0,
    good: 0,
    basic: 0,
    unrated: 0,
  };
  for (const n of rhizomes) counts[qualityBandOf(n.quality_overall)]++;

  const rows: Array<{
    band: QualityBand;
    label: string;
    colorClass: string;
    navKey: HomeNavKey;
  }> = [
    {
      band: "top",
      label: "Top · 精选",
      colorClass: "bg-accent",
      navKey: "quality:top",
    },
    {
      band: "good",
      label: "Good · 良好",
      colorClass: "bg-accent/60",
      navKey: "quality:good",
    },
    {
      band: "basic",
      label: "Basic · 基础",
      colorClass: "bg-text-tertiary/60",
      navKey: "quality:basic",
    },
    {
      band: "unrated",
      label: "Unrated · 未评分",
      colorClass: "bg-text-tertiary/25",
      navKey: "quality:unrated",
    },
  ];

  if (total === 0) {
    return (
      <div className="text-text-tertiary text-[13px] leading-relaxed">
        尚无节点可统计
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {rows.map(({ band, label, colorClass, navKey }) => {
        const count = counts[band];
        const pct = (count / total) * 100;
        const active = activeNav === navKey;
        return (
          <button
            key={band}
            type="button"
            onClick={() => setActiveNav(active ? "all" : navKey)}
            className="w-full text-left space-y-1 group cursor-pointer"
            aria-pressed={active}
            title={
              active ? "再次点击取消筛选" : `筛选 ${label} (${count})`
            }
          >
            <div
              className={clsx(
                metaLabel,
                "flex items-center justify-between transition-colors",
                active
                  ? "text-accent"
                  : "text-text-secondary group-hover:text-text-primary",
              )}
            >
              <span>{label}</span>
              <span className="tabular-nums text-text-tertiary">
                {count} · {pct.toFixed(0)}%
              </span>
            </div>
            <div
              className={clsx(
                "h-[3px] rounded-pill overflow-hidden transition-colors",
                active ? "bg-accent/10" : "bg-border-subtle",
              )}
            >
              <div
                className={clsx(
                  "h-full rounded-pill transition-all",
                  colorClass,
                )}
                style={{ width: `${pct}%` }}
              />
            </div>
          </button>
        );
      })}
    </div>
  );
}

function PulseSparkline() {
  const rhizomes = useGraphStore((s) => s.rhizomes);

  const buckets = useMemo(() => {
    const toKey = (d: Date) =>
      `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
    const now = new Date();
    const days: Array<{ key: string; short: string; count: number }> = [];
    for (let i = 6; i >= 0; i--) {
      const d = new Date(now);
      d.setDate(now.getDate() - i);
      d.setHours(0, 0, 0, 0);
      days.push({
        key: toKey(d),
        short: ["日", "一", "二", "三", "四", "五", "六"][d.getDay()],
        count: 0,
      });
    }
    const idx = new Map(days.map((d, i) => [d.key, i]));
    for (const n of rhizomes) {
      const i = idx.get(toKey(new Date(n.created_at)));
      if (i !== undefined) days[i].count++;
    }
    return days;
  }, [rhizomes]);

  const max = Math.max(1, ...buckets.map((b) => b.count));
  const total = buckets.reduce((s, b) => s + b.count, 0);
  const today = buckets[buckets.length - 1].count;
  const weeklyAvg = total / 7;

  if (total === 0) {
    return (
      <div className="text-text-tertiary text-[13px] leading-relaxed">
        最近 7 天安静无事
      </div>
    );
  }

  return (
    <div className="space-y-3">
      <div className="flex items-baseline gap-2">
        <span className="font-content text-[32px] leading-none tracking-[-0.02em] text-text-primary tabular-nums">
          {total}
        </span>
        <span
          className={clsx(
            metaLabel,
            "text-text-tertiary uppercase tracking-[0.14em]",
          )}
        >
          past 7d · 根话题
        </span>
      </div>

      <div className="flex items-end gap-1 h-10" aria-hidden>
        {buckets.map((d, i) => {
          const isToday = i === buckets.length - 1;
          return (
            <div
              key={d.key}
              className="flex-1 flex items-end"
              title={`${d.key}: ${d.count}`}
            >
              <span
                className={clsx(
                  "w-full rounded-sm transition-colors",
                  isToday ? "bg-accent" : "bg-accent/35",
                )}
                style={{
                  height: `${Math.max(2, (d.count / max) * 40)}px`,
                }}
              />
            </div>
          );
        })}
      </div>

      <div
        className={clsx(
          metaLabel,
          "flex gap-1 text-text-tertiary tabular-nums",
        )}
      >
        {buckets.map((d, i) => (
          <span
            key={d.key}
            className={clsx(
              "flex-1 text-center",
              i === buckets.length - 1 && "text-accent",
            )}
          >
            {d.short}
          </span>
        ))}
      </div>

      <div className={clsx(metaLabel, "text-text-tertiary")}>
        今日{" "}
        <span className="text-accent tabular-nums">{today}</span>
        <span className="text-text-tertiary/50 mx-1.5">·</span>
        周均{" "}
        <span className="text-text-secondary tabular-nums">
          {weeklyAvg.toFixed(1)}
        </span>
      </div>
    </div>
  );
}

const NOTIF_TYPE_META: Record<
  string,
  { glyph: string; label: string; color: string }
> = {
  node_created: { glyph: "+", label: "node", color: "var(--color-accent)" },
  edge_created: { glyph: "→", label: "edge", color: "var(--color-accent-warm)" },
  decision_complete: {
    glyph: "✓",
    label: "decision",
    color: "var(--color-success)",
  },
  orchestration_status: {
    glyph: "~",
    label: "orchestration",
    color: "var(--color-warning)",
  },
  summary_generated: {
    glyph: "¶",
    label: "summary",
    color: "var(--color-node-consensus)",
  },
  quality_scored: {
    glyph: "Q",
    label: "quality",
    color: "var(--color-node-human)",
  },
};

function ActivityFeed() {
  const items = useNotificationStore((s) => s.items).slice(0, 12);

  if (items.length === 0) {
    return (
      <div className="text-text-tertiary text-[13px] leading-relaxed">
        等待第一条事件…
      </div>
    );
  }

  return (
    <ul className="space-y-2.5">
      {items.map((n) => {
        const meta = NOTIF_TYPE_META[n.type] ?? {
          glyph: "·",
          label: n.type,
          color: "var(--color-text-tertiary)",
        };
        return (
          <li key={n.id} className="flex items-start gap-2.5">
            <span
              className="shrink-0 w-4 text-center font-mono text-sm leading-[1.4]"
              style={{ color: meta.color }}
              aria-hidden
            >
              {meta.glyph}
            </span>
            <span className="flex-1 min-w-0">
              <span className="block font-content text-[13.5px] leading-[1.45] text-text-secondary truncate">
                {n.message}
                {n.nodeId && (
                  <span
                    className={clsx(metaLabel, "text-text-tertiary ml-1.5")}
                  >
                    {n.nodeId.slice(0, 6)}
                  </span>
                )}
              </span>
              <span className={clsx(metaLabel, "text-text-tertiary/80")}>
                {meta.label} · {relativeTime(n.timestamp)}
              </span>
            </span>
          </li>
        );
      })}
    </ul>
  );
}

function MyContribution({ rhizomes }: { rhizomes: GraphNodeDTO[] }) {
  const userId = useAuthStore((s) => s.userId);
  const navigate = useNavigate();
  if (!userId) {
    return (
      <div className="text-text-tertiary text-[13px] leading-relaxed">
        登录后查看你的贡献
      </div>
    );
  }

  const mine = rhizomes.filter((n) => n.author_id === userId);
  const count = mine.length;
  const latest = mine.sort(
    (a, b) => Date.parse(b.created_at) - Date.parse(a.created_at),
  )[0];

  return (
    <div className="space-y-3">
      <div className="flex items-baseline gap-2">
        <span className="font-content text-[32px] leading-none tracking-[-0.02em] text-text-primary tabular-nums">
          {count}
        </span>
        <span
          className={clsx(
            metaLabel,
            "text-text-tertiary uppercase tracking-[0.14em]",
          )}
        >
          rhizomes
        </span>
      </div>
      {latest ? (
        <button
          type="button"
          onClick={() => navigate(`/workspace/${latest.node_id}`)}
          className="w-full text-left group"
        >
          <div
            className={clsx(metaLabel, "text-text-tertiary mb-1")}
          >
            most recent
          </div>
          <div className="font-content text-[14px] leading-[1.4] text-text-secondary group-hover:text-accent truncate transition-colors">
            {stripMarkdown(latest.content ?? latest.summary_content).split(
              "\n",
            )[0] || "Untitled"}
          </div>
          <div className={clsx(metaLabel, "text-text-tertiary mt-0.5")}>
            {relativeTime(latest.created_at)}
          </div>
        </button>
      ) : (
        <div className="text-text-tertiary text-[13px] leading-relaxed">
          你还没有发起过话题
        </div>
      )}
    </div>
  );
}

export function HomeRightRail() {
  const rhizomes = useGraphStore((s) => s.rhizomes);

  return (
    <aside
      className="hidden lg:flex w-[300px] shrink-0 h-screen sticky top-0 flex-col bg-bg-canvas border-l border-border-default/70 font-ui overflow-y-auto pt-4"
      aria-label="Home sidebar"
    >
      {/* Desktop User/Notif Controls moved from Header */}
      <div className="px-4 pb-4 mb-2 border-b border-border-subtle flex justify-end">
        <HeaderActions isDesktop />
      </div>

      <SectionCard title="Quality · 质量榜">
        <QualityTop />
      </SectionCard>

      <SectionCard title="Distribution · 质量分布">
        <QualityDistribution />
      </SectionCard>

      <SectionCard title="Pulse · 7 日脉搏">
        <PulseSparkline />
      </SectionCard>

      <SectionCard title="Activity · 实时活动">
        <ActivityFeed />
      </SectionCard>

      <SectionCard title="Mine · 我的贡献">
        <MyContribution rhizomes={rhizomes} />
      </SectionCard>
    </aside>
  );
}
