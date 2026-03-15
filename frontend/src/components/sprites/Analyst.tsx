interface AnalystProps {
  size?: number;
}

// Pixel-art sootball with glasses and a tiny chart
// Bright green accents — the margin watcher
export function Analyst({ size = 32 }: AnalystProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      style={{ imageRendering: "pixelated" }}
    >
      {/* Sootball body */}
      <rect x="4" y="1" width="8" height="1" fill="#1a1a2e" />
      <rect x="3" y="2" width="10" height="1" fill="#1a1a2e" />
      <rect x="2" y="3" width="12" height="1" fill="#1a1a2e" />
      <rect x="1" y="4" width="14" height="1" fill="#1a1a2e" />
      <rect x="1" y="5" width="14" height="1" fill="#1a1a2e" />
      <rect x="1" y="6" width="14" height="1" fill="#1a1a2e" />
      <rect x="1" y="7" width="14" height="1" fill="#1a1a2e" />
      <rect x="2" y="8" width="12" height="1" fill="#1a1a2e" />
      <rect x="3" y="9" width="10" height="1" fill="#1a1a2e" />
      <rect x="4" y="10" width="8" height="1" fill="#1a1a2e" />
      {/* Glasses frames — bold green */}
      <rect x="3" y="4" width="4" height="3" fill="none" stroke="#34d399" strokeWidth="0.8" />
      <rect x="9" y="4" width="4" height="3" fill="none" stroke="#34d399" strokeWidth="0.8" />
      <rect x="7" y="5" width="2" height="1" fill="#34d399" />
      {/* Eyes behind glasses */}
      <rect x="4" y="5" width="2" height="1" fill="#FFFFFF" />
      <rect x="10" y="5" width="2" height="1" fill="#FFFFFF" />
      <rect x="5" y="5" width="1" height="1" fill="#34d399" />
      <rect x="11" y="5" width="1" height="1" fill="#34d399" />
      {/* Little "o" mouth */}
      <rect x="7" y="8" width="2" height="1" fill="#34d399" />
      {/* Tiny legs */}
      <rect x="4" y="11" width="2" height="2" fill="#34d399" />
      <rect x="10" y="11" width="2" height="2" fill="#34d399" />
      <rect x="4" y="13" width="2" height="1" fill="#22b07d" />
      <rect x="10" y="13" width="2" height="1" fill="#22b07d" />
      {/* Tiny chart — pixel bar chart */}
      <rect x="0" y="8" width="1" height="3" fill="#22b07d" />
      <rect x="0" y="6" width="1" height="2" fill="#34d399" />
      <rect x="0" y="9" width="1" height="2" fill="#34d399" />
    </svg>
  );
}
