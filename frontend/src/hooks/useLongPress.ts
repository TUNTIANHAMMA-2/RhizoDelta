import { useCallback, useRef } from "react";
import type { MouseEvent, PointerEvent, TouchEvent } from "react";

interface LongPressOptions {
  threshold?: number;
  moveCancelThreshold?: number;
}

interface Point {
  x: number;
  y: number;
}

const DEFAULT_THRESHOLD = 500;
const DEFAULT_MOVE_CANCEL_THRESHOLD = 8;

function supportsPointerEvents() {
  return typeof window !== "undefined" && "onpointerdown" in window;
}

function distance(a: Point, b: Point) {
  return Math.hypot(a.x - b.x, a.y - b.y);
}

export function useLongPress(
  onLongPress: () => void,
  options: LongPressOptions = {},
) {
  const threshold = options.threshold ?? DEFAULT_THRESHOLD;
  const moveCancelThreshold =
    options.moveCancelThreshold ?? DEFAULT_MOVE_CANCEL_THRESHOLD;
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const startPointRef = useRef<Point | null>(null);
  const triggeredRef = useRef(false);
  const suppressClickRef = useRef(false);

  const clear = useCallback(() => {
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    startPointRef.current = null;
    triggeredRef.current = false;
  }, []);

  const start = useCallback(
    (point: Point) => {
      clear();
      startPointRef.current = point;
      timerRef.current = setTimeout(() => {
        triggeredRef.current = true;
        suppressClickRef.current = true;
        onLongPress();
        timerRef.current = null;
        setTimeout(() => {
          triggeredRef.current = false;
        }, 0);
        setTimeout(() => {
          suppressClickRef.current = false;
        }, 500);
      }, threshold);
    },
    [clear, onLongPress, threshold],
  );

  const move = useCallback(
    (point: Point) => {
      if (!startPointRef.current || triggeredRef.current) return;
      if (distance(startPointRef.current, point) > moveCancelThreshold) {
        clear();
      }
    },
    [clear, moveCancelThreshold],
  );

  const onPointerDown = useCallback(
    (event: PointerEvent<HTMLElement>) => {
      if (!supportsPointerEvents() || event.pointerType === "mouse" && event.button !== 0) {
        return;
      }
      // Nested targets (e.g. recursive comment tree) each attach their own
      // useLongPress handlers. Without stopping propagation, ancestor handlers
      // would all start their own timers and the outermost one wins, leaking
      // the wrong nodeId into the menu.
      event.stopPropagation();
      start({ x: event.clientX, y: event.clientY });
    },
    [start],
  );

  const onPointerMove = useCallback(
    (event: PointerEvent<HTMLElement>) => {
      if (!supportsPointerEvents()) return;
      move({ x: event.clientX, y: event.clientY });
    },
    [move],
  );

  const onTouchStart = useCallback(
    (event: TouchEvent<HTMLElement>) => {
      if (supportsPointerEvents() || event.touches.length !== 1) return;
      const touch = event.touches[0];
      event.stopPropagation();
      start({ x: touch.clientX, y: touch.clientY });
    },
    [start],
  );

  const onTouchMove = useCallback(
    (event: TouchEvent<HTMLElement>) => {
      if (supportsPointerEvents() || event.touches.length !== 1) return;
      const touch = event.touches[0];
      move({ x: touch.clientX, y: touch.clientY });
    },
    [move],
  );

  const onClickCapture = useCallback((event: MouseEvent<HTMLElement>) => {
    if (!suppressClickRef.current) return;
    event.preventDefault();
    event.stopPropagation();
  }, []);

  return {
    onClickCapture,
    onPointerDown,
    onPointerMove,
    onPointerUp: clear,
    onPointerCancel: clear,
    onPointerLeave: clear,
    onTouchStart,
    onTouchMove,
    onTouchEnd: clear,
  };
}
