interface ShipperProps {
  size?: number;
}

export function Shipper({ size = 32 }: ShipperProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 32 32"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
    >
      {/* Hard hat */}
      <rect x="8" y="4" width="16" height="5" rx="2" fill="var(--accent)" />
      <rect x="6" y="8" width="20" height="2" rx="1" fill="var(--accent)" />
      {/* Head */}
      <circle cx="16" cy="14" r="5" fill="var(--text-primary)" />
      {/* Eyes */}
      <circle cx="14" cy="13" r="1" fill="var(--bg-root)" />
      <circle cx="18" cy="13" r="1" fill="var(--bg-root)" />
      {/* Smile */}
      <path d="M14 16 Q16 17.5 18 16" stroke="var(--bg-root)" strokeWidth="0.8" fill="none" strokeLinecap="round" />
      {/* Body */}
      <rect x="12" y="19" width="8" height="6" rx="2" fill="var(--accent)" />
      {/* Legs */}
      <rect x="13" y="25" width="2" height="4" rx="1" fill="var(--text-secondary)" />
      <rect x="17" y="25" width="2" height="4" rx="1" fill="var(--text-secondary)" />
      {/* Clipboard */}
      <rect x="21" y="20" width="5" height="6" rx="1" fill="var(--text-secondary)" />
      <rect x="22" y="21" width="3" height="1" rx="0.5" fill="var(--bg-root)" />
      <rect x="22" y="23" width="3" height="1" rx="0.5" fill="var(--bg-root)" />
    </svg>
  );
}
