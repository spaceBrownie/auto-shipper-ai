import { TrendingUp, TrendingDown } from "lucide-react";

interface KpiCardProps {
  label: string;
  value: string | number;
  trend?: { value: number; direction: "up" | "down" };
  accentColor?: "profit" | "warning" | "danger" | "info" | "accent";
}

const colorMap: Record<string, string> = {
  profit: "var(--profit)",
  warning: "var(--warning)",
  danger: "var(--danger)",
  info: "var(--info)",
  accent: "var(--accent)",
};

export function KpiCard({
  label,
  value,
  trend,
  accentColor = "accent",
}: KpiCardProps) {
  const borderColor = colorMap[accentColor];
  const trendColor =
    trend?.direction === "up" ? "var(--profit)" : "var(--danger)";

  return (
    <div
      className="rounded-lg p-4"
      style={{
        backgroundColor: "var(--bg-surface-1)",
        border: "1px solid var(--border-default)",
        borderLeft: `3px solid ${borderColor}`,
      }}
    >
      <div className="flex items-center justify-between mb-1">
        <span
          style={{
            fontFamily: "'Onest', sans-serif",
            fontWeight: 500,
            fontSize: 12,
            textTransform: "uppercase",
            color: "var(--text-secondary)",
            letterSpacing: "0.03em",
          }}
        >
          {label}
        </span>
        {trend && (
          <span
            className="flex items-center gap-0.5"
            style={{
              fontFamily: "'Martian Mono', monospace",
              fontWeight: 400,
              fontSize: 12,
              color: trendColor,
            }}
          >
            {trend.direction === "up" ? (
              <TrendingUp size={14} />
            ) : (
              <TrendingDown size={14} />
            )}
            {trend.value}
          </span>
        )}
      </div>
      <div
        style={{
          fontFamily: "'Martian Mono', monospace",
          fontWeight: 500,
          fontSize: 36,
          lineHeight: 1,
          color: "var(--text-primary)",
        }}
      >
        {value}
      </div>
    </div>
  );
}
