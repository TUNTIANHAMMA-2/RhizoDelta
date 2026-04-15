import { useEffect } from "react";
import { useUiStore } from "../stores/uiStore";
import { useGraphStore } from "../stores/graphStore";
import { useSse } from "../hooks/useSse";
import { DagCanvas } from "./graph/DagCanvas";
import { ExploreCanvas } from "./graph/ExploreCanvas";
import { RhizoneList } from "./sidebar/RhizoneList";
import { NodeDetailPanel } from "./panels/NodeDetailPanel";
import { EditDraftPanel } from "./panels/EditDraftPanel";
import { ReviewPanel } from "./panels/ReviewPanel";
import { Header } from "./chrome/Header";
import { ToastContainer } from "./feedback/Toast";
import { loadGraphForRoot } from "../lib/loadGraphForRoot";

function CanvasModeSwitch() {
  const canvasMode = useUiStore((s) => s.canvasMode);
  const setCanvasMode = useUiStore((s) => s.setCanvasMode);

  return (
    <div
      style={{
        position: "absolute",
        top: 72,
        left: "var(--space-4)",
        zIndex: 20,
        display: "flex",
        gap: "var(--space-2)",
        padding: "var(--space-2)",
        background: "rgba(255, 255, 255, 0.88)",
        border: "1px solid var(--color-border-default)",
        borderRadius: "var(--radius-lg)",
        boxShadow: "var(--shadow-md)",
        backdropFilter: "blur(12px)",
        WebkitBackdropFilter: "blur(12px)",
      }}
    >
      {[
        ["lineage", "版本视图"],
        ["explore", "探索视图"],
      ].map(([mode, label]) => {
        const active = canvasMode === mode;
        return (
          <button
            key={mode}
            type="button"
            onClick={() => setCanvasMode(mode as "lineage" | "explore")}
            style={{
              border: "none",
              borderRadius: "var(--radius-sm)",
              padding: "var(--space-2) var(--space-4)",
              background: active ? "var(--color-text-primary)" : "transparent",
              color: active ? "var(--color-bg-primary)" : "var(--color-text-secondary)",
              fontWeight: active ? 600 : 500,
              cursor: "pointer",
              fontFamily: "var(--font-ui)",
              fontSize: "var(--font-size-sm)",
              transition: "all var(--transition-fast)",
            }}
          >
            {label}
          </button>
        );
      })}
    </div>
  );
}

export function GraphWorkspace() {
  const leftSidebarOpen = useUiStore((s) => s.leftSidebarOpen);
  const rightPanelMode = useUiStore((s) => s.rightPanelMode);
  const isMobileMenuOpen = useUiStore((s) => s.isMobileMenuOpen);
  const setMobileMenuOpen = useUiStore((s) => s.setMobileMenuOpen);
  const toggleLeftSidebar = useUiStore((s) => s.toggleLeftSidebar);
  const canvasMode = useUiStore((s) => s.canvasMode);

  const loadRhizomes = useGraphStore((s) => s.loadRhizomes);
  const loadLineage = useGraphStore((s) => s.loadLineage);
  const loadChildren = useGraphStore((s) => s.loadChildren);

  useEffect(() => {
    loadRhizomes()
      .then(async () => {
        const rootNodeId = useGraphStore.getState().rhizomes[0]?.node_id;
        if (!rootNodeId) {
          return;
        }
        await loadGraphForRoot(rootNodeId, {
          loadLineage,
          loadChildren,
          onChildrenError: console.error,
        });
      })
      .catch(console.error);
  }, [loadRhizomes, loadLineage, loadChildren]);

  useSse();

  return (
    <div style={{ display: "flex", height: "100vh", overflow: "hidden" }}>
      <Header />

      {/* Mobile hamburger */}
      <button
        className="mobile-menu-btn"
        onClick={() => {
          if (!leftSidebarOpen) toggleLeftSidebar();
          setMobileMenuOpen(!isMobileMenuOpen);
        }}
        aria-label="打开菜单"
        style={{
          position: "fixed",
          top: "var(--space-2)",
          right: "var(--space-4)",
          zIndex: 101,
          background: "rgba(255, 255, 255, 0.85)",
          backdropFilter: "blur(8px)",
          border: "1px solid var(--color-border-default)",
          borderRadius: "var(--radius-sm)",
          padding: "var(--space-2)",
          cursor: "pointer",
          fontFamily: "var(--font-ui)",
          fontSize: "var(--font-size-md)",
        }}
      >
        ☰
      </button>

      {/* Left sidebar — overlay on mobile */}
      {leftSidebarOpen ? (
        <>
          <div className="sidebar-container">
            <RhizoneList />
          </div>
          {/* Mobile backdrop */}
          <div
            className="mobile-backdrop"
            onClick={() => {
              toggleLeftSidebar();
              setMobileMenuOpen(false);
            }}
            style={{
              position: "fixed",
              inset: 0,
              zIndex: 49,
              background: "rgba(55, 53, 47, 0.3)",
            }}
          />
        </>
      ) : (
        /* Collapsed: show expand button */
        <button
          className="sidebar-toggle-btn"
          onClick={toggleLeftSidebar}
          style={{
            position: "fixed",
            top: "50%",
            left: 0,
            transform: "translateY(-50%)",
            zIndex: 50,
            width: 24,
            height: 48,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            background: "rgba(255, 255, 255, 0.85)",
            backdropFilter: "blur(8px)",
            border: "1px solid var(--color-border-default)",
            borderLeft: "none",
            borderRadius: "0 var(--radius-full) var(--radius-full) 0",
            cursor: "pointer",
            color: "var(--color-text-secondary)",
            boxShadow: "var(--shadow-sm)",
            padding: 0,
          }}
          aria-label="展开侧边栏"
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{ marginLeft: -2 }}>
            <polyline points="9 18 15 12 9 6"></polyline>
          </svg>
        </button>
      )}

      {/* Canvas */}
      <div style={{ flex: 1, position: "relative" }}>
        <CanvasModeSwitch />
        {canvasMode === "lineage" ? <DagCanvas /> : <ExploreCanvas />}
      </div>

      {/* Right panel */}
      {rightPanelMode === "detail" && <NodeDetailPanel />}
      {rightPanelMode === "edit" && <EditDraftPanel />}
      {rightPanelMode === "review" && <ReviewPanel />}

      <ToastContainer />

      {/* Responsive CSS */}
      <style>{`
        .sidebar-toggle-btn { transition: all var(--transition-fast); }
        .sidebar-toggle-btn:hover { background: var(--color-bg-hover) !important; color: var(--color-text-primary) !important; }

        .mobile-menu-btn { display: none; }
        .mobile-backdrop { display: none; }
        .sidebar-container { height: 100%; display: flex; flex-direction: column; }

        @media (max-width: 1024px) {
          .mobile-menu-btn { display: block; }
          .mobile-backdrop { display: block; }
          .sidebar-container {
            position: fixed;
            top: 0;
            bottom: 0;
            left: 0;
            z-index: 50;
            background: var(--color-bg-secondary);
            width: 80%;
            max-width: 320px;
          }
        }
      `}</style>
    </div>
  );
}
