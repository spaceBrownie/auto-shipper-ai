import { StatusBadge } from "./StatusBadge";

interface ReserveGaugeProps {
  balance: number;
  health: "HEALTHY" | "CRITICAL";
}

export function ReserveGauge({ balance, health }: ReserveGaugeProps) {
  // Clamp fill percentage 0-100
  const fillPercent = Math.max(0, Math.min(100, balance > 0 ? Math.min(balance / 100, 100) : 0));

  const formattedBalance = new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "USD",
    minimumFractionDigits: 0,
    maximumFractionDigits: 0,
  }).format(balance);

  return (
    <div>
      {/* Bar */}
      <div
        className="w-full rounded-md overflow-hidden"
        style={{
          height: 12,
          backgroundColor: "var(--bg-surface-3)",
        }}
      >
        <div
          className="h-full rounded-md"
          style={{
            width: `${fillPercent}%`,
            background: `linear-gradient(90deg, var(--danger) 0%, var(--warning) 40%, var(--profit) 70%)`,
            transition: "width 600ms ease-out",
          }}
        />
      </div>

      {/* Info below */}
      <div className="flex items-center justify-between mt-2">
        <span
          style={{
            fontFamily: "'Martian Mono', monospace",
            fontWeight: 400,
            fontSize: 14,
            color: "var(--text-primary)",
          }}
        >
          {formattedBalance}
        </span>
        <StatusBadge status={health} />
      </div>
    </div>
  );
}
