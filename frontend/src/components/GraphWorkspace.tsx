import { useUiStore } from "../stores/uiStore";
import { useSse } from "../hooks/useSse";
import { DagCanvas } from "./graph/DagCanvas";
import { RhizoneList } from "./sidebar/RhizoneList";
import { NodeDetailPanel } from "./panels/NodeDetailPanel";
import { EditDraftPanel } from "./panels/EditDraftPanel";
import { Header } from "./chrome/Header";
import { ToastContainer } from "./feedback/Toast";

export function GraphWorkspace() {
  const leftSidebarOpen = useUiStore((s) => s.leftSidebarOpen);
  const rightPanelMode = useUiStore((s) => s.rightPanelMode);
  const isMobileMenuOpen = useUiStore((s) => s.isMobileMenuOpen);
  const setMobileMenuOpen = useUiStore((s) => s.setMobileMenuOpen);
  const toggleLeftSidebar = useUiStore((s) => s.toggleLeftSidebar);

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
        style={{
          display: "none",
          position: "fixed",
          top: "var(--space-2)",
          right: "var(--space-4)",
          zIndex: 101,
          background: "rgba(250, 250, 248, 0.85)",
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
          <RhizoneList />
          {/* Mobile backdrop */}
          <div
            className="mobile-backdrop"
            onClick={() => {
              toggleLeftSidebar();
              setMobileMenuOpen(false);
            }}
            style={{
              display: "none",
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
          onClick={toggleLeftSidebar}
          style={{
            position: "fixed",
            top: 48,
            left: 0,
            zIndex: 50,
            background: "rgba(250, 250, 248, 0.85)",
            backdropFilter: "blur(8px)",
            border: "1px solid var(--color-border-default)",
            borderLeft: "none",
            borderRadius: "0 var(--radius-sm) var(--radius-sm) 0",
            padding: "var(--space-2) var(--space-1)",
            cursor: "pointer",
            color: "var(--color-text-secondary)",
            fontFamily: "var(--font-ui)",
            fontSize: "var(--font-size-sm)",
            lineHeight: 1,
          }}
          aria-label="展开侧边栏"
        >
          &raquo;
        </button>
      )}

      {/* Canvas */}
      <div style={{ flex: 1, position: "relative" }}>
        <DagCanvas />
      </div>

      {/* Right panel */}
      {rightPanelMode === "detail" && <NodeDetailPanel />}
      {rightPanelMode === "edit" && <EditDraftPanel />}

      <ToastContainer />

      {/* Responsive CSS */}
      <style>{`
        @media (max-width: 1024px) {
          .mobile-menu-btn { display: block !important; }
          .mobile-backdrop { display: block !important; }
        }
      `}</style>
    </div>
  );
}
