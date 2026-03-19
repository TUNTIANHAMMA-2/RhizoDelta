import { useEffect, useState } from "react";
import { fetchProvenance } from "../../api/nodes";
import { useGraphStore } from "../../stores/graphStore";
import { useUiStore } from "../../stores/uiStore";
import { Skeleton } from "../feedback/Skeleton";
import { EmptyState } from "../feedback/EmptyState";
import type { GraphNodeDTO } from "../../api/types";

const TYPE_COLOR = {
  Human_Post: "var(--color-node-human)",
  AI_Consensus: "var(--color-node-consensus)",
  Result: "var(--color-node-result)",
} as const;

interface Props {
  nodeId: string;
}

export function ProvenancePanel({ nodeId }: Props) {
  const [items, setItems] = useState<GraphNodeDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const selectNode = useGraphStore((s) => s.selectNode);
  const openDetailPanel = useUiStore((s) => s.openDetailPanel);

  useEffect(() => {
    setLoading(true);
    fetchProvenance(nodeId)
      .then(setItems)
      .catch(() => setItems([]))
      .finally(() => setLoading(false));
  }, [nodeId]);

  if (loading) {
    return (
      <div style={{ display: "flex", flexDirection: "column", gap: "var(--space-3)" }}>
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
    <div style={{ display: "flex", flexDirection: "column", gap: "var(--space-2)" }}>
      {items.map((item) => (
        <button
          key={item.node_id}
          onClick={() => {
            selectNode(item.node_id);
            openDetailPanel(item.node_id);
          }}
          style={{
            display: "flex",
            alignItems: "center",
            gap: "var(--space-3)",
            padding: "var(--space-3)",
            background: "none",
            border: "1px solid var(--color-border-default)",
            borderRadius: "var(--radius-sm)",
            cursor: "pointer",
            textAlign: "left",
            width: "100%",
            transition: "var(--transition-fast)",
          }}
          onMouseEnter={(e) =>
            (e.currentTarget.style.background = "var(--color-bg-hover)")
          }
          onMouseLeave={(e) =>
            (e.currentTarget.style.background = "none")
          }
        >
          <span
            style={{
              width: 6,
              height: 6,
              borderRadius: "var(--radius-full)",
              background: TYPE_COLOR[item.label] ?? "var(--color-text-tertiary)",
              flexShrink: 0,
            }}
          />
          <div style={{ flex: 1, minWidth: 0 }}>
            <div
              style={{
                fontSize: "var(--font-size-sm)",
                color: "var(--color-text-primary)",
                overflow: "hidden",
                textOverflow: "ellipsis",
                whiteSpace: "nowrap",
              }}
            >
              {item.content?.slice(0, 60) ?? item.summary_content?.slice(0, 60) ?? "—"}
            </div>
            <div
              style={{
                fontSize: "var(--font-size-xs)",
                color: "var(--color-text-tertiary)",
              }}
            >
              {item.author_id ?? "Agent"} &middot;{" "}
              {new Date(item.created_at).toLocaleDateString()}
            </div>
          </div>
        </button>
      ))}
    </div>
  );
}
