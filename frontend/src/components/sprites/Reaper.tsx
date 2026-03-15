interface ReaperProps {
  size?: number;
}

export function Reaper({ size = 32 }: ReaperProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 32 32"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
    >
      {/* Hood */}
      <path d="M8 14 Q8 3 16 3 Q24 3 24 14 L22 16 L10 16 Z" fill="var(--danger)" />
      {/* Face (inside hood) */}
      <circle cx="16" cy="12" r="4.5" fill="var(--text-primary)" />
      {/* Eyes — friendly dots */}
      <circle cx="14.5" cy="11.5" r="0.9" fill="var(--bg-root)" />
      <circle cx="17.5" cy="11.5" r="0.9" fill="var(--bg-root)" />
      {/* Friendly smile */}
      <path d="M14 14 Q16 15.5 18 14" stroke="var(--bg-root)" strokeWidth="0.7" fill="none" strokeLinecap="round" />
      {/* Body (robe) */}
      <path d="M10 16 L10 26 Q16 28 22 26 L22 16 Z" fill="var(--danger)" />
      {/* Feet */}
      <rect x="11" y="26" width="3" height="2" rx="1" fill="var(--text-secondary)" />
      <rect x="18" y="26" width="3" height="2" rx="1" fill="var(--text-secondary)" />
      {/* Tiny scythe */}
      <line x1="24" y1="8" x2="24" y2="24" stroke="var(--text-secondary)" strokeWidth="1" />
      <path d="M24 8 Q28 8 28 12 Q27 10 24 10" fill="var(--text-secondary)" />
    </svg>
  );
}
