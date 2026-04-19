import { useEffect, useState } from "react";
import { useGraphStore } from "../../stores/graphStore";
import { useUiStore } from "../../stores/uiStore";
import { useSse } from "../../hooks/useSse";
import { useCommandPalette } from "../../hooks/useCommandPalette";
import { CommandPalette } from "../search/CommandPalette";
import { EditDraftPanel } from "../panels/EditDraftPanel";
import { ReviewPanel } from "../panels/ReviewPanel";
import { ToastContainer } from "../feedback/Toast";
import { Header } from "../chrome/Header";
import { HomeSidebar } from "./HomeSidebar";
import { HomeMainColumn } from "./HomeMainColumn";
import { HomeRightRail } from "./HomeRightRail";

export function HomePage() {
  const loadRhizomes = useGraphStore((s) => s.loadRhizomes);
  const rightPanelMode = useUiStore((s) => s.rightPanelMode);
  const commandPalette = useCommandPalette();
  const [mobileNavOpen, setMobileNavOpen] = useState(false);

  useEffect(() => {
    loadRhizomes().catch(console.error);
  }, [loadRhizomes]);

  useSse();

  return (
    <div className="min-h-screen flex bg-bg-canvas font-ui">
      <Header hideLogo />

      {/* Mobile hamburger */}
      <button
        type="button"
        onClick={() => setMobileNavOpen((v) => !v)}
        className="md:hidden fixed top-3 left-3 z-[101] w-10 h-10 flex items-center justify-center bg-bg-elevated/90 backdrop-blur-md border border-border-default rounded-sm text-text-secondary"
        aria-label={mobileNavOpen ? "关闭菜单" : "打开菜单"}
      >
        <span className="text-lg leading-none">{mobileNavOpen ? "×" : "☰"}</span>
      </button>

      {/* Left sidebar — md+ static, mobile drawer */}
      <div className="hidden md:block">
        <HomeSidebar />
      </div>
      {mobileNavOpen && (
        <>
          <div
            className="md:hidden fixed inset-0 z-[60] bg-text-primary/20 backdrop-blur-[2px]"
            onClick={() => setMobileNavOpen(false)}
            aria-hidden
          />
          <div className="md:hidden fixed inset-y-0 left-0 z-[61] w-[80%] max-w-[320px] shadow-xl">
            <HomeSidebar />
          </div>
        </>
      )}

      {/* Main column */}
      <HomeMainColumn onOpenSearch={commandPalette.open} />

      {/* Right rail */}
      <HomeRightRail />

      {/* Shared chrome */}
      {rightPanelMode === "edit" && <EditDraftPanel />}
      {rightPanelMode === "review" && <ReviewPanel />}
      <CommandPalette
        isOpen={commandPalette.isOpen}
        onClose={commandPalette.close}
      />
      <ToastContainer />
    </div>
  );
}
