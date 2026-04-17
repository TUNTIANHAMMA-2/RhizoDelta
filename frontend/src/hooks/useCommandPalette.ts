import { useCallback, useEffect, useState } from "react";

/**
 * Manages Command Palette open/close state and the global Cmd+K / Ctrl+K shortcut.
 *
 * - Mac: Cmd+K
 * - Other: Ctrl+K
 * - Cleans up the global keydown listener on unmount.
 */
export function useCommandPalette() {
  const [isOpen, setIsOpen] = useState(false);

  const open = useCallback(() => setIsOpen(true), []);
  const close = useCallback(() => setIsOpen(false), []);
  const toggle = useCallback(() => setIsOpen((v) => !v), []);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      const modKey = e.metaKey || e.ctrlKey;
      if (modKey && e.key === "k") {
        e.preventDefault();
        toggle();
      }
    };

    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [toggle]);

  return { isOpen, open, close, toggle };
}
