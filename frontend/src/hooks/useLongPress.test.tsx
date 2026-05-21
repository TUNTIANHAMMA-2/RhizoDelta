import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useLongPress } from "./useLongPress";

function Harness({ onLongPress }: { onLongPress: () => void }) {
  const handlers = useLongPress(onLongPress);
  return (
    <button type="button" {...handlers}>
      target
    </button>
  );
}

function NestedHarness({
  onParentLongPress,
  onChildLongPress,
}: {
  onParentLongPress: () => void;
  onChildLongPress: () => void;
}) {
  const parentHandlers = useLongPress(onParentLongPress);
  const childHandlers = useLongPress(onChildLongPress);
  return (
    <div data-testid="parent" {...parentHandlers}>
      <div data-testid="child" {...childHandlers}>
        child
      </div>
    </div>
  );
}

describe("useLongPress", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    Object.defineProperty(window, "onpointerdown", { value: null, configurable: true });
  });

  it("fires after the threshold", () => {
    const onLongPress = vi.fn();
    render(<Harness onLongPress={onLongPress} />);

    fireEvent.pointerDown(screen.getByRole("button"), {
      pointerType: "touch",
      clientX: 0,
      clientY: 0,
    });
    vi.advanceTimersByTime(550);

    expect(onLongPress).toHaveBeenCalledTimes(1);
  });

  it("cancels when movement exceeds the threshold", () => {
    const onLongPress = vi.fn();
    render(<Harness onLongPress={onLongPress} />);

    const target = screen.getByRole("button");
    fireEvent.pointerDown(target, { pointerType: "touch", clientX: 0, clientY: 0 });
    fireEvent.pointerMove(target, { pointerType: "touch", clientX: 16, clientY: 0 });
    vi.advanceTimersByTime(550);

    expect(onLongPress).not.toHaveBeenCalled();
  });

  it("ignores multi-touch fallback starts", () => {
    const onLongPress = vi.fn();
    delete (window as Partial<Window>).onpointerdown;
    render(<Harness onLongPress={onLongPress} />);

    fireEvent.touchStart(screen.getByRole("button"), {
      touches: [
        { clientX: 0, clientY: 0 },
        { clientX: 4, clientY: 4 },
      ],
    });
    vi.advanceTimersByTime(550);

    expect(onLongPress).not.toHaveBeenCalled();
  });

  it("does not fire ancestor handlers when nested target wins", () => {
    const onParentLongPress = vi.fn();
    const onChildLongPress = vi.fn();
    render(
      <NestedHarness
        onParentLongPress={onParentLongPress}
        onChildLongPress={onChildLongPress}
      />,
    );

    fireEvent.pointerDown(screen.getByTestId("child"), {
      pointerType: "touch",
      clientX: 0,
      clientY: 0,
    });
    vi.advanceTimersByTime(550);

    expect(onChildLongPress).toHaveBeenCalledTimes(1);
    expect(onParentLongPress).not.toHaveBeenCalled();
  });
});
