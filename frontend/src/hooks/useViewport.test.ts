import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import { useViewport } from "./useViewport";

const listeners = new Set<(event: MediaQueryListEvent) => void>();
let viewportWidth = 1280;

function setViewport(width: number) {
  viewportWidth = width;
  Object.defineProperty(window, "innerWidth", {
    value: width,
    configurable: true,
  });
  listeners.forEach((listener) =>
    listener({ matches: width <= 1024 } as MediaQueryListEvent),
  );
  window.dispatchEvent(new Event("resize"));
}

describe("useViewport", () => {
  beforeEach(() => {
    listeners.clear();
    Object.defineProperty(window, "matchMedia", {
      value: (query: string) => ({
        matches: viewportWidth <= 1024,
        media: query,
        addEventListener: (_type: string, listener: (event: MediaQueryListEvent) => void) => {
          listeners.add(listener);
        },
        removeEventListener: (_type: string, listener: (event: MediaQueryListEvent) => void) => {
          listeners.delete(listener);
        },
      }),
      configurable: true,
    });
    setViewport(1280);
  });

  it("returns desktop by default and updates across the mobile breakpoint", () => {
    const { result } = renderHook(() => useViewport());

    expect(result.current).toEqual({ isMobile: false, width: 1280 });

    act(() => setViewport(390));
    expect(result.current).toEqual({ isMobile: true, width: 390 });

    act(() => setViewport(1200));
    expect(result.current).toEqual({ isMobile: false, width: 1200 });
  });
});
