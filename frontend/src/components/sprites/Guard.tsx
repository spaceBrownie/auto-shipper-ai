interface GuardProps {
  size?: number;
}

export function Guard({ size = 32 }: GuardProps) {
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
      {/* Stern eyes — flat brows */}
      <rect x="12" y="8" width="3" height="1" rx="0.5" fill="var(--bg-root)" />
      <rect x="17" y="8" width="3" height="1" rx="0.5" fill="var(--bg-root)" />
      <circle cx="13.5" cy="10.5" r="0.8" fill="var(--bg-root)" />
      <circle cx="18.5" cy="10.5" r="0.8" fill="var(--bg-root)" />
      {/* Flat mouth */}
      <line x1="14" y1="13" x2="18" y2="13" stroke="var(--bg-root)" strokeWidth="0.8" strokeLinecap="round" />
      {/* Body */}
      <rect x="12" y="16" width="8" height="7" rx="2" fill="var(--info)" />
      {/* Legs */}
      <rect x="13" y="23" width="2" height="4" rx="1" fill="var(--text-secondary)" />
      <rect x="17" y="23" width="2" height="4" rx="1" fill="var(--text-secondary)" />
      {/* Shield */}
      <path d="M3 16 L7 14 L11 16 L11 21 Q7 24 3 21 Z" fill="var(--info)" stroke="var(--text-primary)" strokeWidth="0.5" />
      <path d="M5 17 L7 16 L9 17 L9 20 Q7 22 5 20 Z" fill="var(--text-primary)" />
    </svg>
  );
}
