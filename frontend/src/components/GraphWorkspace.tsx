import { useEffect } from "react";
import { useUiStore } from "../stores/uiStore";
import { useGraphStore } from "../stores/graphStore";
import { useSse } from "../hooks/useSse";
import { useCommandPalette } from "../hooks/useCommandPalette";
import { DagCanvas } from "./graph/DagCanvas";
import { ExploreCanvas } from "./graph/ExploreCanvas";
import { RhizoneList } from "./sidebar/RhizoneList";
import { NodeDetailPanel } from "./panels/NodeDetailPanel";
import { EditDraftPanel } from "./panels/EditDraftPanel";
import { ReviewPanel } from "./panels/ReviewPanel";
import { CommandPalette } from "./search/CommandPalette";
import { Header } from "./chrome/Header";
import { ToastContainer } from "./feedback/Toast";
import { loadGraphForRoot } from "../lib/loadGraphForRoot";
import clsx from "clsx";

function CanvasModeSwitch() {
  const canvasMode = useUiStore((s) => s.canvasMode);
  const setCanvasMode = useUiStore((s) => s.setCanvasMode);

  return (
    <div className="absolute top-[72px] left-4 z-20 flex gap-[2px] p-1 bg-[rgba(253,252,249,0.88)] border border-border-default rounded-lg shadow-md backdrop-blur-md backdrop-saturate-150">
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
            className={clsx(
              "border-none rounded-md px-4 py-2 cursor-pointer font-ui text-sm transition-[all] duration-[var(--transition-fast)] tracking-[0.01em]",
              active
                ? "bg-text-primary text-bg-primary font-semibold"
                : "bg-transparent text-text-secondary font-medium",
            )}
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

  const commandPalette = useCommandPalette();

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
    <div className="flex h-screen overflow-hidden bg-bg-canvas">
      <Header />

      {/* Mobile hamburger */}
      <button
        className="mobile-menu-btn fixed top-2 right-4 z-[101] bg-[rgba(253,252,249,0.88)] backdrop-blur-md border border-border-default rounded-sm p-2 cursor-pointer font-ui text-md text-text-secondary"
        onClick={() => {
          if (!leftSidebarOpen) toggleLeftSidebar();
          setMobileMenuOpen(!isMobileMenuOpen);
        }}
        aria-label="打开菜单"
      >
        ☰
      </button>

      {/* Left sidebar — overlay on mobile */}
      {leftSidebarOpen ? (
        <>
          <div className="sidebar-container">
            <RhizoneList />
          </div>
          <div
            className="mobile-backdrop fixed inset-0 z-[49] bg-[rgba(26,29,27,0.25)] backdrop-blur-[2px]"
            onClick={() => {
              toggleLeftSidebar();
              setMobileMenuOpen(false);
            }}
          />
        </>
      ) : (
        <button
          className="sidebar-toggle-btn fixed top-1/2 left-0 -translate-y-1/2 z-50 w-6 h-12 flex items-center justify-center bg-[rgba(253,252,249,0.88)] backdrop-blur-md border border-border-default border-l-0 rounded-r-full cursor-pointer text-text-tertiary shadow-sm p-0"
          onClick={toggleLeftSidebar}
          aria-label="展开侧边栏"
        >
          <svg
            width="14"
            height="14"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
            className="-ml-[2px]"
          >
            <polyline points="9 18 15 12 9 6"></polyline>
          </svg>
        </button>
      )}

      {/* Canvas */}
      <div className="flex-1 relative">
        <CanvasModeSwitch />
        {canvasMode === "lineage" ? <DagCanvas /> : <ExploreCanvas />}
      </div>

      {/* Right panel */}
      {rightPanelMode === "detail" && <NodeDetailPanel />}
      {rightPanelMode === "edit" && <EditDraftPanel />}
      {rightPanelMode === "review" && <ReviewPanel />}

      <CommandPalette isOpen={commandPalette.isOpen} onClose={commandPalette.close} />

      <ToastContainer />

      {/* Responsive CSS (media-query-based, keep as raw CSS) */}
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
