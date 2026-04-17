export interface SkeletonProps {
  variant?: "text" | "circular" | "rectangular";
  width?: string | number;
  height?: string | number;
  className?: string;
}

const VARIANT_RADIUS: Record<NonNullable<SkeletonProps["variant"]>, string> = {
  circular: "rounded-full",
  text: "rounded-sm",
  rectangular: "rounded-md",
};

export function Skeleton({
  variant = "text",
  width,
  height,
  className = "",
}: SkeletonProps) {
  const resolvedWidth = width ?? (variant === "circular" ? 40 : "100%");
  const resolvedHeight =
    height ?? (variant === "circular" ? 40 : variant === "text" ? 14 : 80);

  return (
    <>
      <style>{`
        @keyframes skeleton-pulse {
          0%, 100% { opacity: 1; }
          50% { opacity: 0.4; }
        }
      `}</style>
      <div
        className={`bg-bg-hover ${VARIANT_RADIUS[variant]} ${className}`}
        style={{
          animation: "skeleton-pulse 1.5s ease-in-out infinite",
          width: resolvedWidth,
          height: resolvedHeight,
        }}
      />
    </>
  );
}
