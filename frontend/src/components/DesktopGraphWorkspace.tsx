import { useCallback, useEffect } from "react";
import { useNavigate, useParams } from "react-router-dom";
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
    <div className="absolute top-[72px] left-4 z-20 flex gap-[2px] p-1 bg-[rgba(253,252,249,0.88)] border border-border-default rounded-md shadow-md backdrop-blur-md backdrop-saturate-150">
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
                ? "bg-accent-deep text-bg-primary font-semibold"
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

export function DesktopGraphWorkspace() {
  const { rhizomeId } = useParams<{ rhizomeId?: string }>();
  const navigate = useNavigate();
  const leftSidebarOpen = useUiStore((s) => s.leftSidebarOpen);
  const rightPanelMode = useUiStore((s) => s.rightPanelMode);
  const isMobileMenuOpen = useUiStore((s) => s.isMobileMenuOpen);
  const setMobileMenuOpen = useUiStore((s) => s.setMobileMenuOpen);
  const toggleLeftSidebar = useUiStore((s) => s.toggleLeftSidebar);
  const canvasMode = useUiStore((s) => s.canvasMode);

  useCommandPalette();

  const loadRhizomes = useGraphStore((s) => s.loadRhizomes);
  const loadTopologyContext = useGraphStore((s) => s.loadTopologyContext);
  const selectNode = useGraphStore((s) => s.selectNode);

  const closeMobileRhizoneSheet = useCallback(() => {
    if (leftSidebarOpen) toggleLeftSidebar();
    setMobileMenuOpen(false);
  }, [leftSidebarOpen, toggleLeftSidebar, setMobileMenuOpen]);

  const handleBackHome = useCallback(() => {
    selectNode(null);
    useUiStore.getState().closeRightPanel();
    setMobileMenuOpen(false);
    navigate("/");
  }, [navigate, selectNode, setMobileMenuOpen]);

  useEffect(() => {
    // Priority: route param → first available rhizome
    if (rhizomeId) {
      loadGraphForRoot(rhizomeId, {
        loadTopologyContext,
      });
      // Also load the rhizome list in the background for the sidebar
      loadRhizomes();
      return;
    }

    loadRhizomes()
      .then(async () => {
        const rootNodeId = useGraphStore.getState().rhizomes[0]?.node_id;
        if (!rootNodeId) {
          return;
        }
        await loadGraphForRoot(rootNodeId, {
          loadTopologyContext,
        });
      })
  }, [rhizomeId, loadRhizomes, loadTopologyContext]);

  useSse();

  return (
    <div className="flex h-screen overflow-hidden bg-bg-canvas">
      <Header />

      {/* Mobile hamburger */}
      <button
        type="button"
        className="lg:hidden fixed top-2 left-4 z-[101] h-10 rounded-md bg-[rgba(253,252,249,0.88)] backdrop-blur-md border border-border-default px-3 cursor-pointer font-ui text-sm text-text-secondary shadow-sm"
        onClick={handleBackHome}
        aria-label="返回主页面"
      >
        ← 主页
      </button>
      <button
        className="lg:hidden fixed top-2 right-4 z-[101] bg-[rgba(253,252,249,0.88)] backdrop-blur-md border border-border-default rounded-sm p-2 cursor-pointer font-ui text-md text-text-secondary"
        onClick={() => setMobileMenuOpen(!isMobileMenuOpen)}
        aria-label={isMobileMenuOpen ? "关闭菜单" : "打开菜单"}
      >
        {isMobileMenuOpen ? "×" : "☰"}
      </button>

      {/* Desktop sidebar */}
      {leftSidebarOpen ? (
        <div className="hidden lg:flex h-full flex-col">
          <RhizoneList />
        </div>
      ) : (
        <button
          className="hidden lg:flex fixed top-1/2 left-0 -translate-y-1/2 z-50 w-6 h-12 items-center justify-center bg-[rgba(253,252,249,0.88)] backdrop-blur-md border border-border-default border-l-0 rounded-r-pill cursor-pointer text-text-tertiary shadow-sm p-0 transition-all hover:bg-bg-hover hover:text-text-primary"
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

      {/* Mobile rhizone bottom sheet */}
      {isMobileMenuOpen && (
        <>
          <div className="lg:hidden fixed inset-x-0 bottom-0 z-50 max-h-[72vh] overflow-hidden rounded-t-[28px] border border-border-default/70 border-b-0 bg-bg-secondary shadow-2xl">
            <div className="mx-auto mt-3 mb-1 h-1.5 w-12 rounded-pill bg-border-default/80" aria-hidden />
            <RhizoneList />
          </div>
          <div
            className="lg:hidden fixed inset-0 z-[49] bg-[rgba(26,29,27,0.25)] backdrop-blur-[2px]"
            onClick={closeMobileRhizoneSheet}
          />
        </>
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

      <CommandPalette />

      <ToastContainer />
    </div>
  );
}
