import { useEffect } from "react";
import { useUiStore } from "../stores/uiStore";

/**
 * Installs the global Cmd+K / Ctrl+K listener that toggles the command palette.
 * Mount once per top-level page that should respond to the shortcut. The open
 * state itself lives in useUiStore so any chrome element can open the palette
 * without prop drilling.
 *
 * - Mac: Cmd+K
 * - Other: Ctrl+K
 */
export function useCommandPalette() {
  const toggle = useUiStore((s) => s.toggleCommandPalette);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === "k") {
        e.preventDefault();
        toggle();
      }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [toggle]);
}
