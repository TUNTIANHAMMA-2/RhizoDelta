import { lazy, Suspense } from "react";
import { useViewport } from "../hooks/useViewport";

const MobileDiscussionTreeView = lazy(() =>
  import("./mobile/MobileDiscussionTreeView").then((module) => ({
    default: module.MobileDiscussionTreeView,
  })),
);

const DesktopGraphWorkspace = lazy(() =>
  import("./DesktopGraphWorkspace").then((module) => ({
    default: module.DesktopGraphWorkspace,
  })),
);

export function GraphWorkspace() {
  const { isMobile } = useViewport();

  return (
    <Suspense fallback={<div className="spinner" aria-label="Loading…" />}>
      {isMobile ? <MobileDiscussionTreeView /> : <DesktopGraphWorkspace />}
    </Suspense>
  );
}
