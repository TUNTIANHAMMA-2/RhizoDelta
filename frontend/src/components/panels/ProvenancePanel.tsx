import { useEffect, useState } from "react";
import { fetchProvenance } from "../../api/nodes";
import { useGraphStore } from "../../stores/graphStore";
import { useUiStore } from "../../stores/uiStore";
import { Skeleton } from "../feedback/Skeleton";
import { EmptyState } from "../feedback/EmptyState";
import { AuthorLabel } from "../shared/AuthorLabel";
import type { GraphNodeDTO } from "../../api/types";

const TYPE_COLOR = {
  Human_Post: "var(--color-node-human)",
  AI_Consensus: "var(--color-node-consensus)",
  Result: "var(--color-node-result)",
} as const;

interface Props {
  nodeId: string;
}

function ProvenanceItem({ item, onSelect }: { item: GraphNodeDTO; onSelect: () => void }) {
  return (
    <button
      onClick={onSelect}
      className="flex items-center gap-3 p-3 bg-transparent hover:bg-bg-hover border border-border-default rounded-sm cursor-pointer text-left w-full transition-[background] duration-[var(--transition-fast)]"
    >
      <span
        className="w-[6px] h-[6px] rounded-full shrink-0"
        style={{
          background: TYPE_COLOR[item.label] ?? "var(--color-text-tertiary)",
        }}
      />
      <div className="flex-1 min-w-0">
        <div className="text-sm text-text-primary overflow-hidden text-ellipsis whitespace-nowrap">
          {item.content?.slice(0, 60) ?? item.summary_content?.slice(0, 60) ?? "—"}
        </div>
        <div className="text-xs text-text-tertiary">
          <AuthorLabel
            displayName={item.author_display_name}
            username={item.author_username}
            authorId={item.author_id}
            agentVersion={item.agent_version}
          /> &middot;{" "}
          {new Date(item.created_at).toLocaleDateString()}
        </div>
      </div>
    </button>
  );
}

export function ProvenancePanel({ nodeId }: Props) {
  const [items, setItems] = useState<GraphNodeDTO[]>([]);
  const [loadedNodeId, setLoadedNodeId] = useState<string | null>(null);
  const loading = loadedNodeId !== nodeId;
  const selectNode = useGraphStore((s) => s.selectNode);
  const openDetailPanel = useUiStore((s) => s.openDetailPanel);

  useEffect(() => {
    let cancelled = false;
    fetchProvenance(nodeId)
      .then((nextItems) => {
        if (!cancelled) setItems(nextItems);
      })
      .catch(() => {
        if (!cancelled) setItems([]);
      })
      .finally(() => {
        if (!cancelled) setLoadedNodeId(nodeId);
      });
    return () => {
      cancelled = true;
    };
  }, [nodeId]);

  if (loading) {
    return (
      <div className="flex flex-col gap-3">
        <Skeleton variant="rectangular" height={48} />
        <Skeleton variant="rectangular" height={48} />
        <Skeleton variant="rectangular" height={48} />
      </div>
    );
  }

  if (items.length === 0) {
    return <EmptyState message="暂无确权溯源数据" />;
  }

  return (
    <div className="flex flex-col gap-2">
      {items.map((item) => (
        <ProvenanceItem
          key={item.node_id}
          item={item}
          onSelect={() => {
            selectNode(item.node_id);
            openDetailPanel(item.node_id);
          }}
        />
      ))}
    </div>
  );
}
