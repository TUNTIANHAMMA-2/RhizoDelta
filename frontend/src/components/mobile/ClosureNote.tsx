import clsx from "clsx";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import type { DiscussionArtifact } from "../../api/types";
import { mobileDiscussionTreeLabels as labels } from "../../i18n/labels";
import { useDiscussionTreeStore } from "../../stores/discussionTreeStore";
import { previewText } from "./mobileUtils";

function SparkleIcon() {
  return (
    <svg
      viewBox="0 0 16 16"
      width={12}
      height={12}
      fill="none"
      stroke="currentColor"
      strokeWidth={1.4}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      <path d="M8 1.5L9.2 6.2L13.5 8L9.2 9.8L8 14.5L6.8 9.8L2.5 8L6.8 6.2Z" />
      <path d="M13 1.5L13.5 3L15 3.5L13.5 4L13 5.5L12.5 4L11 3.5L12.5 3Z" opacity="0.7" />
    </svg>
  );
}

function PinIcon() {
  return (
    <svg
      viewBox="0 0 16 16"
      width={12}
      height={12}
      fill="none"
      stroke="currentColor"
      strokeWidth={1.4}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      <path d="M8 1.5C5.8 1.5 4 3.3 4 5.5C4 8.5 8 14.5 8 14.5C8 14.5 12 8.5 12 5.5C12 3.3 10.2 1.5 8 1.5Z" />
      <circle cx={8} cy={5.5} r={1.5} />
    </svg>
  );
}

function ChevronIcon() {
  return (
    <svg
      viewBox="0 0 16 16"
      width={10}
      height={10}
      fill="none"
      stroke="currentColor"
      strokeWidth={1.6}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      <path d="M4 6L8 10L12 6" />
    </svg>
  );
}

export function ClosureNote({ artifact }: { artifact: DiscussionArtifact }) {
  const expanded = useDiscussionTreeStore((s) => s.expandedArtifactIds.has(artifact.node_id));
  const nodesById = useDiscussionTreeStore((s) => s.nodesById);
  const toggleArtifactExpanded = useDiscussionTreeStore((s) => s.toggleArtifactExpanded);
  const setActiveArtifact = useDiscussionTreeStore((s) => s.setActiveArtifact);
  const sourcesInView = artifact.source_node_ids.filter((id) => nodesById.has(id));
  const sourcesOutsideView = Math.max(0, artifact.source_count - sourcesInView.length);
  const isResult = artifact.kind === "RESULT";
  const accentColor = isResult ? "var(--color-node-result)" : "var(--color-node-consensus)";
  const glowColor = isResult ? "var(--color-glow-result)" : "var(--color-glow-consensus)";

  const onToggle = () => {
    toggleArtifactExpanded(artifact.node_id);
    setActiveArtifact(expanded ? null : artifact.node_id);
  };

  return (
    <aside
      className="my-3 overflow-hidden rounded-l-sm rounded-r-md bg-gradient-to-r from-bg-secondary/95 to-bg-secondary/30"
      style={{
        borderLeft: `4px solid ${accentColor}`,
        boxShadow: `0 1px 2px rgba(26,29,27,0.04), -1px 0 10px -2px ${glowColor}`,
      }}
    >
      <div className="px-3 py-2.5">
        <button
          type="button"
          onClick={onToggle}
          className="flex w-full items-center justify-between gap-3 border-none bg-transparent p-0 text-left font-ui text-xs text-text-secondary transition-opacity duration-150 hover:opacity-80"
          aria-expanded={expanded}
          aria-label={expanded ? labels.artifactCollapse : labels.artifactExpand}
        >
          <span
            className="inline-flex items-center gap-1.5 font-semibold tracking-wide"
            style={{ color: accentColor }}
          >
            {isResult ? <PinIcon /> : <SparkleIcon />}
            {isResult ? labels.artifactResult : labels.artifactConsensus}
          </span>
          <span
            className={clsx(
              "inline-flex items-center transition-transform duration-200 ease-out",
              expanded && "rotate-180",
            )}
          >
            <ChevronIcon />
          </span>
        </button>
        <div
          className={clsx(
            "mt-2 font-content text-sm leading-[1.55] text-text-primary",
            !expanded && "line-clamp-2",
            expanded && "animate-fade-in",
          )}
        >
          <ReactMarkdown remarkPlugins={[remarkGfm]}>{artifact.body ?? ""}</ReactMarkdown>
        </div>
        {expanded && (
          <div className="mt-2 animate-fade-in font-ui text-xs text-text-secondary">
            <p>
              {labels.sourceCountInsideView(sourcesInView.length)}
              {sourcesOutsideView > 0
                ? ` · ${labels.sourceCountOutsideHint(sourcesOutsideView)}`
                : ""}
            </p>
            {sourcesInView.length > 0 && (
              <ul className="mt-2 flex flex-col gap-1">
                {sourcesInView.map((sourceId) => {
                  const source = nodesById.get(sourceId);
                  return (
                    <li
                      key={sourceId}
                      className="rounded-sm bg-bg-primary/80 px-2 py-1 text-text-secondary shadow-[0_1px_0_rgba(26,29,27,0.03)]"
                    >
                      {previewText(source?.content, 56)}
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        )}
      </div>
    </aside>
  );
}
