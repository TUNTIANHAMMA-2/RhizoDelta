import clsx from "clsx";

export interface WordMarkProps {
  className?: string;
}

export function WordMark({ className }: WordMarkProps) {
  return (
    <span
      className={clsx(
        "font-content font-light tracking-[-0.02em] text-text-primary",
        className,
      )}
    >
      <span aria-hidden="true">
        RhizoDelt<span className="text-accent">△</span>
      </span>
      <span className="sr-only">RhizoDelta</span>
    </span>
  );
}
