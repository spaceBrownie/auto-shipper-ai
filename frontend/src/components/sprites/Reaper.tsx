interface ReaperProps {
  size?: number;
}

// Pixel-art sootball with a tiny scythe and hood
// Friendly death sprite — red but cute, Miyazaki vibes
export function Reaper({ size = 32 }: ReaperProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      style={{ imageRendering: "pixelated" }}
    >
      {/* Hood peak */}
      <rect x="6" y="0" width="4" height="1" fill="#ef4444" />
      <rect x="5" y="1" width="6" height="1" fill="#ef4444" />
      {/* Sootball body with hood edges */}
      <rect x="4" y="2" width="8" height="1" fill="#ef4444" />
      <rect x="3" y="2" width="1" height="1" fill="#dc2626" />
      <rect x="12" y="2" width="1" height="1" fill="#dc2626" />
      <rect x="2" y="3" width="12" height="1" fill="#1a1a2e" />
      <rect x="1" y="4" width="14" height="1" fill="#1a1a2e" />
      <rect x="1" y="5" width="14" height="1" fill="#1a1a2e" />
      <rect x="1" y="6" width="14" height="1" fill="#1a1a2e" />
      <rect x="1" y="7" width="14" height="1" fill="#1a1a2e" />
      <rect x="2" y="8" width="12" height="1" fill="#1a1a2e" />
      <rect x="3" y="9" width="10" height="1" fill="#1a1a2e" />
      <rect x="4" y="10" width="8" height="1" fill="#1a1a2e" />
      {/* Eyes — glowing red dots, friendly */}
      <rect x="4" y="5" width="2" height="2" fill="#FFFFFF" />
      <rect x="10" y="5" width="2" height="2" fill="#FFFFFF" />
      <rect x="5" y="5" width="1" height="1" fill="#ef4444" />
      <rect x="11" y="5" width="1" height="1" fill="#ef4444" />
      {/* Wavy smile — friendly reaper */}
      <rect x="6" y="8" width="1" height="1" fill="#ef4444" />
      <rect x="7" y="9" width="2" height="1" fill="#ef4444" />
      <rect x="9" y="8" width="1" height="1" fill="#ef4444" />
      {/* Tiny legs */}
      <rect x="4" y="11" width="2" height="2" fill="#ef4444" />
      <rect x="10" y="11" width="2" height="2" fill="#ef4444" />
      <rect x="4" y="13" width="2" height="1" fill="#dc2626" />
      <rect x="10" y="13" width="2" height="1" fill="#dc2626" />
      {/* Pixel scythe */}
      <rect x="14" y="1" width="1" height="10" fill="#a1a1aa" />
      <rect x="15" y="1" width="1" height="1" fill="#a1a1aa" />
      <rect x="15" y="2" width="1" height="2" fill="#d4d4d8" />
      <rect x="14" y="4" width="1" height="1" fill="#d4d4d8" />
    </svg>
  );
}
