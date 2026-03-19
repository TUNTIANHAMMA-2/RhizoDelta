import { useUiStore, type NodeTab } from "../../stores/uiStore";
import { useGraphStore } from "../../stores/graphStore";
import { ProvenancePanel } from "./ProvenancePanel";
import { AssociationPanel } from "./AssociationPanel";
import { AuditPanel } from "./AuditPanel";

const TABS: { id: NodeTab; label: string }[] = [
  { id: "details", label: "详情" },
  { id: "provenance", label: "确权溯源" },
  { id: "association", label: "关联" },
  { id: "audit", label: "审计" },
];

const TYPE_COLOR = {
  Human_Post: "var(--color-node-human)",
  AI_Consensus: "var(--color-node-consensus)",
  Result: "var(--color-node-result)",
} as const;

export function NodeDetailPanel() {
  const payload = useUiStore((s) => s.rightPanelPayload);
  const closePanel = useUiStore((s) => s.closeRightPanel);
  const activeTab = useUiStore((s) => s.activeNodeTab);
  const setActiveTab = useUiStore((s) => s.setActiveNodeTab);
  const nodes = useGraphStore((s) => s.nodes);

  if (!payload) return null;
  const node = nodes.get(payload.nodeId);
  if (!node) return null;

  return (
    <aside
      className="rd-panel"
      onWheel={(e) => e.stopPropagation()}
      onTouchMove={(e) => e.stopPropagation()}
      style={{
        width: 360,
        minWidth: 360,
        borderLeft: "1px solid var(--color-border-default)",
        background: "var(--color-bg-primary)",
        display: "flex",
        flexDirection: "column",
        fontFamily: "var(--font-ui)",
        height: "100%",
      }}
    >
      {/* Header */}
      <div
        style={{
          padding: "var(--space-4)",
          borderBottom: "1px solid var(--color-border-default)",
        }}
      >
        <div
          style={{
            display: "flex",
            justifyContent: "space-between",
            alignItems: "center",
            marginBottom: "var(--space-2)",
          }}
        >
          <div style={{ display: "flex", alignItems: "center", gap: "var(--space-2)" }}>
            <span
              style={{
                width: 8,
                height: 8,
                borderRadius: "var(--radius-full)",
                background: TYPE_COLOR[node.label] ?? "var(--color-text-tertiary)",
              }}
            />
            <span style={{ fontWeight: 600, fontSize: "var(--font-size-md)" }}>
              {node.label.replace("_", " ")}
            </span>
          </div>
          <button
            onClick={closePanel}
            style={{
              background: "none",
              border: "none",
              cursor: "pointer",
              fontSize: "var(--font-size-md)",
              color: "var(--color-text-secondary)",
            }}
          >
            &times;
          </button>
        </div>
        <div
          style={{
            fontSize: "var(--font-size-xs)",
            color: "var(--color-text-secondary)",
          }}
        >
          {node.author_id ?? node.agent_version ?? "System"} &middot;{" "}
          {new Date(node.created_at).toLocaleString()}
        </div>
      </div>

      {/* Tabs */}
      <div
        role="tablist"
        aria-label="节点详情分类"
        style={{
          display: "flex",
          borderBottom: "1px solid var(--color-border-default)",
          padding: "0 var(--space-4)",
        }}
      >
        {TABS.map((tab) => (
          <button
            key={tab.id}
            role="tab"
            aria-selected={activeTab === tab.id}
            aria-controls={`tabpanel-${tab.id}`}
            id={`tab-${tab.id}`}
            onClick={() => setActiveTab(tab.id)}
            style={{
              padding: "var(--space-3) var(--space-3)",
              background: "none",
              border: "none",
              cursor: "pointer",
              borderBottom:
                activeTab === tab.id
                  ? "2px solid var(--color-accent)"
                  : "2px solid transparent",
              color:
                activeTab === tab.id
                  ? "var(--color-text-primary)"
                  : "var(--color-text-tertiary)",
              fontFamily: "var(--font-ui)",
              fontSize: "var(--font-size-xs)",
              fontWeight: activeTab === tab.id ? 600 : 400,
              transition: "var(--transition-fast)",
            }}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Content */}
      <div
        id={`tabpanel-${activeTab}`}
        role="tabpanel"
        aria-labelledby={`tab-${activeTab}`}
        style={{ flex: 1, overflowY: "auto", padding: "var(--space-4)" }}
      >
        {activeTab === "details" && (
          <>
            <div
              style={{
                fontFamily: "var(--font-content)",
                fontSize: "var(--font-size-base)",
                lineHeight: 1.6,
                marginBottom: "var(--space-6)",
              }}
            >
              {node.content ?? node.summary_content ?? "No content"}
            </div>
            <div
              style={{
                fontSize: "var(--font-size-xs)",
                color: "var(--color-text-secondary)",
                borderTop: "1px solid var(--color-border-default)",
                paddingTop: "var(--space-3)",
                display: "flex",
                flexDirection: "column",
                gap: "var(--space-1)",
              }}
            >
              <div>node_id: {node.node_id}</div>
              <div>has_embedding: {String(node.has_embedding)}</div>
              {node.operation_id && <div>operation_id: {node.operation_id}</div>}
            </div>
          </>
        )}
        {activeTab === "provenance" && (
          <ProvenancePanel nodeId={payload.nodeId} />
        )}
        {activeTab === "association" && (
          <AssociationPanel nodeId={payload.nodeId} />
        )}
        {activeTab === "audit" && <AuditPanel nodeId={payload.nodeId} />}
      </div>
    </aside>
  );
}
