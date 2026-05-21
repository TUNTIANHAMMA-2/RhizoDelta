import { useMemo, useSyncExternalStore } from "react";

// 与 Tailwind lg 断点对齐：移动讨论树在 width <= 1024px 时启用。
const MOBILE_QUERY = "(max-width: 1024px)";
const SERVER_SNAPSHOT = { isMobile: false, width: 1280 };

interface ViewportSnapshot {
  isMobile: boolean;
  width: number;
}

let cachedSnapshot: ViewportSnapshot = SERVER_SNAPSHOT;

function subscribe(callback: () => void) {
  if (typeof window === "undefined") {
    return () => undefined;
  }
  const mql = window.matchMedia(MOBILE_QUERY);
  mql.addEventListener("change", callback);
  window.addEventListener("resize", callback);
  return () => {
    mql.removeEventListener("change", callback);
    window.removeEventListener("resize", callback);
  };
}

function getSnapshot(): ViewportSnapshot {
  if (typeof window === "undefined") {
    return SERVER_SNAPSHOT;
  }
  const next = {
    isMobile: window.matchMedia(MOBILE_QUERY).matches,
    width: window.innerWidth,
  };
  if (
    cachedSnapshot.isMobile === next.isMobile &&
    cachedSnapshot.width === next.width
  ) {
    return cachedSnapshot;
  }
  cachedSnapshot = next;
  return cachedSnapshot;
}

function getServerSnapshot(): ViewportSnapshot {
  return SERVER_SNAPSHOT;
}

export function useViewport() {
  const snapshot = useSyncExternalStore(
    subscribe,
    getSnapshot,
    getServerSnapshot,
  );

  return useMemo(
    () => ({ isMobile: snapshot.isMobile, width: snapshot.width }),
    [snapshot.isMobile, snapshot.width],
  );
}
