interface ScoutProps {
  size?: number;
}

export function Scout({ size = 32 }: ScoutProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 32 32"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
    >
      {/* Explorer hat */}
      <ellipse cx="16" cy="6" rx="8" ry="2" fill="#2dd4bf" />
      <rect x="10" y="2" width="12" height="5" rx="3" fill="#2dd4bf" />
      {/* Head */}
      <circle cx="16" cy="12" r="5.5" fill="var(--text-primary)" />
      {/* Eyes — curious, wide */}
      <circle cx="14" cy="11.5" r="1.2" fill="var(--bg-root)" />
      <circle cx="18" cy="11.5" r="1.2" fill="var(--bg-root)" />
      <circle cx="14.3" cy="11.2" r="0.4" fill="var(--text-primary)" />
      <circle cx="18.3" cy="11.2" r="0.4" fill="var(--text-primary)" />
      {/* Smile */}
      <path d="M14 14.5 Q16 16 18 14.5" stroke="var(--bg-root)" strokeWidth="0.7" fill="none" strokeLinecap="round" />
      {/* Body */}
      <rect x="12" y="17.5" width="8" height="6" rx="2" fill="#2dd4bf" />
      {/* Legs */}
      <rect x="13" y="23.5" width="2" height="4" rx="1" fill="var(--text-secondary)" />
      <rect x="17" y="23.5" width="2" height="4" rx="1" fill="var(--text-secondary)" />
      {/* Binoculars */}
      <rect x="22" y="17" width="3" height="2.5" rx="1" fill="var(--text-secondary)" />
      <rect x="25" y="17" width="3" height="2.5" rx="1" fill="var(--text-secondary)" />
      <line x1="21" y1="19" x2="22" y2="18" stroke="var(--text-secondary)" strokeWidth="0.8" />
    </svg>
  );
}
