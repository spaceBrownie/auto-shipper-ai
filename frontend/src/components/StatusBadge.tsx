interface StatusBadgeProps {
  status: string;
  size?: "sm" | "default";
}

function getStatusColor(status: string): { bg: string; text: string } {
  switch (status) {
    case "Ideation":
    case "ValidationPending":
      return { bg: "var(--info-dim)", text: "var(--info)" };
    case "CostGating":
    case "StressTesting":
    case "Paused":
      return { bg: "var(--warning-dim)", text: "var(--warning)" };
    case "Listed":
    case "Scaled":
    case "ACTIVE":
    case "VALIDATED":
    case "CLEARED":
    case "HEALTHY":
      return { bg: "var(--profit-dim)", text: "var(--profit)" };
    case "Terminated":
    case "FAILED":
    case "CRITICAL":
      return { bg: "var(--danger-dim)", text: "var(--danger)" };
    default:
      return { bg: "var(--bg-surface-3)", text: "var(--text-secondary)" };
  }
}

export function StatusBadge({ status, size = "default" }: StatusBadgeProps) {
  const { bg, text } = getStatusColor(status);
  const fontSize = size === "sm" ? 10 : 12;
  const py = size === "sm" ? 1 : 2;
  const px = size === "sm" ? 6 : 8;
  const dotSize = size === "sm" ? 4 : 6;

  return (
    <span
      className="inline-flex items-center gap-1.5 rounded-full"
      style={{
        backgroundColor: bg,
        color: text,
        fontFamily: "'Onest', sans-serif",
        fontWeight: 500,
        fontSize,
        padding: `${py}px ${px}px`,
        lineHeight: 1.4,
      }}
    >
      <span
        className="inline-block rounded-full flex-shrink-0"
        style={{
          width: dotSize,
          height: dotSize,
          backgroundColor: text,
        }}
      />
      {status}
    </span>
  );
}
