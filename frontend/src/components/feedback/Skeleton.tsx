export interface SkeletonProps {
  variant?: "text" | "circular" | "rectangular";
  width?: string | number;
  height?: string | number;
  className?: string;
}

export function Skeleton({
  variant = "text",
  width,
  height,
  className = "",
}: SkeletonProps) {
  const baseStyle: React.CSSProperties = {
    background: "var(--color-bg-hover)",
    animation: "skeleton-pulse 1.5s ease-in-out infinite",
    borderRadius:
      variant === "circular"
        ? "var(--radius-full)"
        : variant === "text"
          ? "var(--radius-sm)"
          : "var(--radius-md)",
    width: width ?? (variant === "circular" ? 40 : "100%"),
    height:
      height ?? (variant === "circular" ? 40 : variant === "text" ? 14 : 80),
  };

  return (
    <>
      <style>{`
        @keyframes skeleton-pulse {
          0%, 100% { opacity: 1; }
          50% { opacity: 0.4; }
        }
      `}</style>
      <div className={className} style={baseStyle} />
    </>
  );
}
