import { useEffect, useState } from "react";
import { fetchAuditList } from "../../api/audit";
import { rollbackDecision, rollbackFork } from "../../api/decisions";
import { useAuthStore } from "../../stores/authStore";
import { useUiStore } from "../../stores/uiStore";
import { Skeleton } from "../feedback/Skeleton";
import { EmptyState } from "../feedback/EmptyState";
import { ConfirmDialog } from "../modals/ConfirmDialog";
import type { AuditRecord, AuditDetail, DecisionType } from "../../api/types";
import { fetchAuditDetail } from "../../api/audit";

const DECISION_TYPE_COLOR: Record<string, string> = {
  MERGE: "var(--color-node-consensus)",
  BRANCH: "var(--color-node-human)",
  INJECT: "var(--color-accent)",
  MATERIALIZE: "var(--color-node-result)",
  FORK: "var(--color-warning)",
  CROSS_SYNTH: "var(--color-node-result)",
  JOIN: "var(--color-node-consensus)",
};

interface Props {
  nodeId: string;
}

export function AuditPanel({ nodeId }: Props) {
  const [items, setItems] = useState<AuditRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [expandedDetail, setExpandedDetail] = useState<AuditDetail | null>(null);
  const [rollbackTarget, setRollbackTarget] = useState<AuditRecord | null>(null);
  const [filterType, setFilterType] = useState<string>("");
  const [filterOperator, setFilterOperator] = useState<string>("");
  const [filterSince, setFilterSince] = useState<string>("");
  const [filterUntil, setFilterUntil] = useState<string>("");
  const isAdmin = useAuthStore((s) => s.hasRole("ADMIN"));
  const addToast = useUiStore((s) => s.addToast);

  const load = (cursor?: string) => {
    setLoading(true);
    fetchAuditList({
      node_id: nodeId,
      after: cursor,
      limit: 20,
      ...(filterType ? { type: filterType as DecisionType } : {}),
      ...(filterOperator ? { operator_id: filterOperator } : {}),
      ...(filterSince ? { since: filterSince } : {}),
      ...(filterUntil ? { until: filterUntil } : {}),
    })
      .then((res) => {
        const newItems = res?.records ?? [];
        setItems((prev) => (cursor ? [...prev, ...newItems] : newItems));
        setNextCursor(res?.next_cursor ?? null);
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    setItems([]);
    setNextCursor(null);
    setExpandedId(null);
    setExpandedDetail(null);
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filterType, filterOperator, filterSince, filterUntil, nodeId]);

  const handleRollback = async () => {
    if (!rollbackTarget) return;
    try {
      if (rollbackTarget.decision_type === "FORK") {
        if (!rollbackTarget.operation_id) {
          addToast({ type: "error", message: "缺少 operation_id，无法回滚 Fork" });
          return;
        }
        await rollbackFork(rollbackTarget.operation_id);
      } else {
        await rollbackDecision(rollbackTarget.decision_id);
      }
      addToast({ type: "success", message: "回滚成功" });
      setRollbackTarget(null);
      setItems([]);
      load();
    } catch {
      addToast({ type: "error", message: "回滚失败" });
    }
  };

  if (loading && (!items || items.length === 0)) {
    return (
      <div style={{ display: "flex", flexDirection: "column", gap: "var(--space-3)" }}>
        <Skeleton variant="rectangular" height={60} />
        <Skeleton variant="rectangular" height={60} />
        <Skeleton variant="rectangular" height={60} />
      </div>
    );
  }

  if (!items || items.length === 0) {
    return <EmptyState message="暂无审计记录" />;
  }

  return (
    <>
      <div
        style={{
          display: "flex",
          flexWrap: "wrap",
          gap: "var(--space-2)",
          marginBottom: "var(--space-3)",
          alignItems: "center",
        }}
      >
        <select
          value={filterType}
          onChange={(e) => setFilterType(e.target.value)}
          style={{
            fontFamily: "var(--font-ui)",
            fontSize: "var(--font-size-xs)",
            padding: "2px var(--space-2)",
            border: "1px solid var(--color-border-default)",
            borderRadius: "var(--radius-sm)",
            background: "var(--color-bg-primary)",
            color: "var(--color-text-primary)",
          }}
        >
          <option value="">All types</option>
          <option value="MERGE">MERGE</option>
          <option value="BRANCH">BRANCH</option>
          <option value="INJECT">INJECT</option>
          <option value="MATERIALIZE">MATERIALIZE</option>
          <option value="FORK">FORK</option>
          <option value="CROSS_SYNTH">CROSS_SYNTH</option>
          <option value="JOIN">JOIN</option>
        </select>
        <input
          type="text"
          placeholder="Operator ID"
          value={filterOperator}
          onChange={(e) => setFilterOperator(e.target.value)}
          style={{
            fontFamily: "var(--font-ui)",
            fontSize: "var(--font-size-xs)",
            padding: "2px var(--space-2)",
            border: "1px solid var(--color-border-default)",
            borderRadius: "var(--radius-sm)",
            background: "var(--color-bg-primary)",
            color: "var(--color-text-primary)",
            width: 120,
          }}
        />
        <input
          type="date"
          value={filterSince}
          onChange={(e) => setFilterSince(e.target.value)}
          style={{
            fontFamily: "var(--font-ui)",
            fontSize: "var(--font-size-xs)",
            padding: "2px var(--space-2)",
            border: "1px solid var(--color-border-default)",
            borderRadius: "var(--radius-sm)",
            background: "var(--color-bg-primary)",
            color: "var(--color-text-primary)",
          }}
        />
        <input
          type="date"
          value={filterUntil}
          onChange={(e) => setFilterUntil(e.target.value)}
          style={{
            fontFamily: "var(--font-ui)",
            fontSize: "var(--font-size-xs)",
            padding: "2px var(--space-2)",
            border: "1px solid var(--color-border-default)",
            borderRadius: "var(--radius-sm)",
            background: "var(--color-bg-primary)",
            color: "var(--color-text-primary)",
          }}
        />
      </div>
      <div style={{ display: "flex", flexDirection: "column", gap: 0 }}>
        {items.map((audit) => {
          const expanded = expandedId === audit.decision_id;
          return (
            <div
              key={audit.decision_id}
              style={{
                borderLeft: "2px solid var(--color-border-default)",
                paddingLeft: "var(--space-4)",
                paddingBottom: "var(--space-4)",
                position: "relative",
              }}
            >
              {/* Timeline dot */}
              <span
                style={{
                  position: "absolute",
                  left: -5,
                  top: 2,
                  width: 8,
                  height: 8,
                  borderRadius: "var(--radius-full)",
                  background:
                    DECISION_TYPE_COLOR[audit.decision_type] ??
                    "var(--color-text-tertiary)",
                }}
              />

              <button
                onClick={() => {
                  const newId = expanded ? null : audit.decision_id;
                  setExpandedId(newId);
                  setExpandedDetail(null);
                  if (newId) {
                    fetchAuditDetail(newId).then((d) => setExpandedDetail(d ?? null)).catch(() => {});
                  }
                }}
                style={{
                  background: "none",
                  border: "none",
                  cursor: "pointer",
                  textAlign: "left",
                  padding: 0,
                  width: "100%",
                }}
              >
                <div
                  style={{
                    display: "flex",
                    alignItems: "center",
                    gap: "var(--space-2)",
                    marginBottom: "var(--space-1)",
                  }}
                >
                  <span
                    style={{
                      display: "inline-flex",
                      padding: "1px var(--space-2)",
                      borderRadius: "var(--radius-full)",
                      background:
                        (DECISION_TYPE_COLOR[audit.decision_type] ?? "#ccc") +
                        "1a",
                      color:
                        DECISION_TYPE_COLOR[audit.decision_type] ??
                        "var(--color-text-secondary)",
                      fontSize: "var(--font-size-xs)",
                      fontWeight: 500,
                    }}
                  >
                    {audit.decision_type}
                  </span>
                  <span
                    style={{
                      fontSize: "var(--font-size-xs)",
                      color: "var(--color-text-tertiary)",
                    }}
                  >
                    {audit.operator_id}
                  </span>
                </div>
                <div
                  style={{
                    fontSize: "var(--font-size-xs)",
                    color: "var(--color-text-tertiary)",
                  }}
                >
                  {new Date(audit.created_at).toLocaleString()}
                </div>
              </button>

              {expanded && (
                <div
                  style={{
                    marginTop: "var(--space-2)",
                    padding: "var(--space-3)",
                    background: "var(--color-bg-secondary)",
                    borderRadius: "var(--radius-sm)",
                    fontSize: "var(--font-size-xs)",
                  }}
                >
                  <div style={{ marginBottom: "var(--space-1)" }}>
                    <strong>reason:</strong> {audit.reason}
                  </div>
                  <div style={{ marginBottom: "var(--space-1)" }}>
                    <strong>operator:</strong> {audit.operator_type} /{" "}
                    {audit.operator_id}
                  </div>
                  {expandedDetail?.synthesized_from && expandedDetail.synthesized_from.length > 0 && (
                    <div>
                      <strong>synthesized_from:</strong>{" "}
                      {expandedDetail.synthesized_from.join(", ")}
                    </div>
                  )}
                  {isAdmin && (
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        setRollbackTarget(audit);
                      }}
                      style={{
                        marginTop: "var(--space-2)",
                        background: "none",
                        border: "none",
                        cursor: "pointer",
                        color: "var(--color-danger)",
                        fontSize: "var(--font-size-xs)",
                        fontWeight: 500,
                      }}
                    >
                      回滚此决策
                    </button>
                  )}
                </div>
              )}
            </div>
          );
        })}

        {nextCursor && (
          <button
            onClick={() => load(nextCursor)}
            disabled={loading}
            style={{
              background: "none",
              border: "1px solid var(--color-border-default)",
              borderRadius: "var(--radius-sm)",
              padding: "var(--space-2)",
              cursor: "pointer",
              fontFamily: "var(--font-ui)",
              fontSize: "var(--font-size-xs)",
              color: "var(--color-text-secondary)",
            }}
          >
            {loading ? "加载中..." : "加载更多"}
          </button>
        )}
      </div>

      <ConfirmDialog
        isOpen={rollbackTarget !== null}
        title="确认回滚决策"
        description={
          rollbackTarget?.decision_type === "FORK"
            ? `将回滚 Fork 操作 ${rollbackTarget?.operation_id?.slice(0, 8) ?? "?"}...，该批次下所有分支节点及其关系将被批量软删除。`
            : `将回滚决策 ${rollbackTarget?.decision_id.slice(0, 8)}...（${rollbackTarget?.decision_type}），相关节点和关系将被软删除。`
        }
        isDestructive
        confirmText="确认回滚"
        onConfirm={handleRollback}
        onCancel={() => setRollbackTarget(null)}
      />
    </>
  );
}
