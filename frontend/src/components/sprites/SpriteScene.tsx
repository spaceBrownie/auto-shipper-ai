import type { ReactNode } from "react";

interface SpriteSceneProps {
  children: ReactNode;
  message?: string;
  animation?: "idle" | "celebrate" | "alert" | "walk";
}

const animationClasses: Record<string, string> = {
  idle: "sprite",
  celebrate: "sprite--celebrate",
  alert: "sprite--alert",
  walk: "sprite--walk",
};

export function SpriteScene({
  children,
  message,
  animation = "idle",
}: SpriteSceneProps) {
  const animClass = animationClasses[animation] || "sprite";

  return (
    <div className="inline-flex flex-col items-center gap-1">
      {message && (
        <div
          className="relative rounded-md px-3 py-1.5 mb-1"
          style={{
            backgroundColor: "var(--bg-surface-2)",
            border: "1px solid var(--border-default)",
            fontFamily: "'Onest', sans-serif",
            fontWeight: 400,
            fontSize: 12,
            color: "var(--text-secondary)",
            maxWidth: 200,
            textAlign: "center",
          }}
        >
          {message}
          {/* Speech bubble arrow */}
          <div
            className="absolute left-1/2 -translate-x-1/2"
            style={{
              bottom: -5,
              width: 0,
              height: 0,
              borderLeft: "5px solid transparent",
              borderRight: "5px solid transparent",
              borderTop: "5px solid var(--border-default)",
            }}
          />
        </div>
      )}
      <div className={animClass}>{children}</div>
    </div>
  );
}
