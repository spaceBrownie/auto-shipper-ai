interface AnalystProps {
  size?: number;
}

export function Analyst({ size = 32 }: AnalystProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 32 32"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
    >
      {/* Head */}
      <circle cx="16" cy="10" r="6" fill="var(--text-primary)" />
      {/* Glasses */}
      <rect x="10" y="8" width="5" height="4" rx="1" stroke="var(--bg-root)" strokeWidth="0.8" fill="none" />
      <rect x="17" y="8" width="5" height="4" rx="1" stroke="var(--bg-root)" strokeWidth="0.8" fill="none" />
      <line x1="15" y1="10" x2="17" y2="10" stroke="var(--bg-root)" strokeWidth="0.8" />
      {/* Eyes behind glasses */}
      <circle cx="12.5" cy="10" r="0.8" fill="var(--bg-root)" />
      <circle cx="19.5" cy="10" r="0.8" fill="var(--bg-root)" />
      {/* Body */}
      <rect x="12" y="16" width="8" height="7" rx="2" fill="var(--profit)" />
      {/* Legs */}
      <rect x="13" y="23" width="2" height="4" rx="1" fill="var(--text-secondary)" />
      <rect x="17" y="23" width="2" height="4" rx="1" fill="var(--text-secondary)" />
      {/* Tiny chart in hand */}
      <rect x="21" y="17" width="6" height="5" rx="1" fill="var(--bg-surface-2)" />
      <polyline points="22,21 24,19 25,20 26,18" stroke="var(--profit)" strokeWidth="0.8" fill="none" strokeLinecap="round" />
    </svg>
  );
}
