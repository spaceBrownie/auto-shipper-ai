interface ScoutProps {
  size?: number;
}

// Pixel-art sootball with antenna/radar — teal, curious wide eyes
// The demand signal hunter
export function Scout({ size = 32 }: ScoutProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      style={{ imageRendering: "pixelated" }}
    >
      {/* Antenna — blinks via CSS */}
      <rect x="7" y="0" width="2" height="1" fill="#2dd4bf" />
      <rect x="6" y="0" width="1" height="1" fill="#5eead4" className="sprite-blink" />
      <rect x="9" y="0" width="1" height="1" fill="#5eead4" className="sprite-blink" />
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
      {/* Big curious eyes — wide and sparkly */}
      <rect x="3" y="4" width="3" height="3" fill="#FFFFFF" />
      <rect x="10" y="4" width="3" height="3" fill="#FFFFFF" />
      <rect x="4" y="5" width="2" height="2" fill="#2dd4bf" />
      <rect x="10" y="5" width="2" height="2" fill="#2dd4bf" />
      <rect x="5" y="5" width="1" height="1" fill="#FFFFFF" />
      <rect x="11" y="5" width="1" height="1" fill="#FFFFFF" />
      {/* Excited open mouth */}
      <rect x="7" y="8" width="2" height="1" fill="#2dd4bf" />
      {/* Tiny legs — bouncy */}
      <rect x="4" y="11" width="2" height="2" fill="#2dd4bf" />
      <rect x="10" y="11" width="2" height="2" fill="#2dd4bf" />
      <rect x="3" y="13" width="2" height="1" fill="#14b8a6" />
      <rect x="11" y="13" width="2" height="1" fill="#14b8a6" />
      {/* Radar waves — teal pulses */}
      <rect x="15" y="3" width="1" height="1" fill="#2dd4bf" opacity="0.6" />
      <rect x="15" y="5" width="1" height="1" fill="#2dd4bf" opacity="0.4" />
      <rect x="15" y="7" width="1" height="1" fill="#2dd4bf" opacity="0.2" />
    </svg>
  );
}
