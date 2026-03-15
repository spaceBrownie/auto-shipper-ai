interface VendorScoreBarProps {
  score: number;
  label?: string;
}

function getScoreColor(score: number): string {
  if (score < 40) return "var(--danger)";
  if (score <= 70) return "var(--warning)";
  return "var(--profit)";
}

export function VendorScoreBar({ score, label }: VendorScoreBarProps) {
  const color = getScoreColor(score);
  const clamped = Math.max(0, Math.min(100, score));

  return (
    <div>
      {label && (
        <div
          className="mb-1"
          style={{
            fontFamily: "'Onest', sans-serif",
            fontWeight: 500,
            fontSize: 12,
            color: "var(--text-secondary)",
          }}
        >
          {label}
        </div>
      )}
      <div className="flex items-center gap-3">
        <div
          className="flex-1 rounded-full overflow-hidden"
          style={{
            height: 8,
            backgroundColor: "var(--bg-surface-3)",
          }}
        >
          <div
            className="h-full rounded-full"
            style={{
              width: `${clamped}%`,
              backgroundColor: color,
              transition: "width 400ms ease-out",
            }}
          />
        </div>
        <span
          style={{
            fontFamily: "'Martian Mono', monospace",
            fontWeight: 400,
            fontSize: 14,
            color,
            minWidth: 36,
            textAlign: "right",
          }}
        >
          {score}
        </span>
      </div>
    </div>
  );
}
