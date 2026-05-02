import clsx from "clsx";
import { useNavigate } from "react-router-dom";
import type { GraphNodeDTO, NodeLabel } from "../../api/types";
import { stripMarkdown } from "../../lib/markdown";
import { AuthorLabel } from "../shared/AuthorLabel";
import { metaLabel, relativeTime } from "../../lib/typography";

interface RhizomeCardProps {
  node: GraphNodeDTO;
}

const LABEL_META: Record<
  NodeLabel,
  { en: string; zh: string; colorVar: string; glyph: string }
> = {
  Human_Post: {
    en: "Human",
    zh: "人类",
    colorVar: "var(--color-node-human)",
    glyph: "○",
  },
  AI_Consensus: {
    en: "AI Consensus",
    zh: "共识",
    colorVar: "var(--color-node-consensus)",
    glyph: "◇",
  },
  Result: {
    en: "Result",
    zh: "结果",
    colorVar: "var(--color-node-result)",
    glyph: "△",
  },
};

function splitTitleAndBody(text: string): { title: string; body: string } {
  const trimmed = text.trim();
  if (!trimmed) return { title: "Untitled", body: "" };
  const firstNewline = trimmed.indexOf("\n");
  if (firstNewline === -1) {
    return { title: trimmed, body: "" };
  }
  return {
    title: trimmed.slice(0, firstNewline).trim() || "Untitled",
    body: trimmed.slice(firstNewline + 1).trim(),
  };
}

export function RhizomeCard({ node }: RhizomeCardProps) {
  const navigate = useNavigate();
  const labelMeta = LABEL_META[node.label];
  const plainSource = stripMarkdown(node.content ?? node.summary_content);
  const { title, body } = splitTitleAndBody(plainSource);
  const summary = node.summary_content
    ? stripMarkdown(node.summary_content)
    : body;
  const quality = node.quality_overall;

  const openGraph = () => navigate(`/workspace/${node.node_id}`);

  return (
    <article
      className="group relative flex gap-5 border-b border-border-subtle bg-bg-elevated hover:bg-bg-hover/40 transition-colors cursor-pointer py-6 pl-5 pr-6"
      onClick={openGraph}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          openGraph();
        }
      }}
      tabIndex={0}
      role="link"
      aria-label={`Open rhizome: ${title}`}
    >
      {/* Left label column */}
      <div
        className="shrink-0 flex flex-col items-center gap-2 w-14 pt-1"
        aria-hidden
      >
        <span
          className="inline-block w-[3px] h-10 rounded-pill"
          style={{ background: labelMeta.colorVar }}
        />
        <span
          className="font-mono text-[20px] leading-none"
          style={{ color: labelMeta.colorVar }}
        >
          {labelMeta.glyph}
        </span>
        <span
          className={clsx(metaLabel, "text-text-tertiary text-center")}
          style={{ letterSpacing: "0.06em" }}
        >
          {labelMeta.en}
        </span>
      </div>

      {/* Main column */}
      <div className="flex-1 min-w-0 space-y-2">
        <h3 className="font-content text-[19px] leading-[1.35] tracking-[-0.01em] text-text-primary line-clamp-2 group-hover:text-accent transition-colors">
          {title}
        </h3>

        {summary && (
          <p className="font-content text-[14.5px] leading-[1.55] text-text-secondary line-clamp-2 font-light">
            {summary}
          </p>
        )}

        <div
          className={clsx(
            metaLabel,
            "flex items-center gap-3 text-text-tertiary pt-1 flex-wrap",
          )}
        >
          <span className="flex items-center gap-1.5">
            <span className="inline-block w-1 h-1 rounded-full bg-text-tertiary/60" />
            <AuthorLabel
              displayName={node.author_display_name}
              username={node.author_username}
              authorId={node.author_id}
            />
          </span>
          <span className="text-text-tertiary/50">·</span>
          <time
            dateTime={node.created_at}
            title={new Date(node.created_at).toLocaleString()}
            className="tabular-nums"
          >
            {relativeTime(node.created_at)}
          </time>
          {typeof quality === "number" && (
            <>
              <span className="text-text-tertiary/50">·</span>
              <span className="flex items-center gap-1 tabular-nums">
                <span className="text-accent">Q</span>
                <span>{quality.toFixed(2)}</span>
              </span>
            </>
          )}
          {node.has_embedding && (
            <>
              <span className="text-text-tertiary/50">·</span>
              <span className="text-accent/80" title="Embedding indexed">
                vec·indexed
              </span>
            </>
          )}
        </div>
      </div>

      {/* Right affordance — hover arrow */}
      <div
        className="self-center shrink-0 w-6 font-content text-text-tertiary/40 group-hover:text-accent group-hover:translate-x-1 transition-all"
        aria-hidden
      >
        ↗
      </div>
    </article>
  );
}
