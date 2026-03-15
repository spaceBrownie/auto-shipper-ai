interface ShipperProps {
  size?: number;
}

// Pixel-art sootball with a hard hat and tiny legs
// Inspired by Miyazaki's soot sprites + Space Invaders
export function Shipper({ size = 32 }: ShipperProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      style={{ imageRendering: "pixelated" }}
    >
      {/* Hard hat — bold amber */}
      <rect x="3" y="0" width="10" height="1" fill="#FFAA00" />
      <rect x="2" y="1" width="12" height="1" fill="#FFAA00" />
      <rect x="1" y="2" width="14" height="1" fill="#E59400" />
      {/* Sootball body — fluffy dark blob */}
      <rect x="3" y="3" width="10" height="1" fill="#1a1a2e" />
      <rect x="2" y="4" width="12" height="1" fill="#1a1a2e" />
      <rect x="1" y="5" width="14" height="1" fill="#1a1a2e" />
      <rect x="1" y="6" width="14" height="1" fill="#1a1a2e" />
      <rect x="1" y="7" width="14" height="1" fill="#1a1a2e" />
      <rect x="2" y="8" width="12" height="1" fill="#1a1a2e" />
      <rect x="3" y="9" width="10" height="1" fill="#1a1a2e" />
      <rect x="4" y="10" width="8" height="1" fill="#1a1a2e" />
      {/* Big sparkly eyes */}
      <rect x="4" y="5" width="2" height="2" fill="#FFFFFF" />
      <rect x="10" y="5" width="2" height="2" fill="#FFFFFF" />
      <rect x="5" y="5" width="1" height="1" fill="#FFAA00" />
      <rect x="11" y="5" width="1" height="1" fill="#FFAA00" />
      {/* Tiny smile */}
      <rect x="6" y="8" width="1" height="1" fill="#FFAA00" />
      <rect x="9" y="8" width="1" height="1" fill="#FFAA00" />
      <rect x="7" y="9" width="2" height="1" fill="#FFAA00" />
      {/* Tiny legs — pixel stubs */}
      <rect x="4" y="11" width="2" height="2" fill="#FFAA00" />
      <rect x="10" y="11" width="2" height="2" fill="#FFAA00" />
      <rect x="4" y="13" width="2" height="1" fill="#E59400" />
      <rect x="10" y="13" width="2" height="1" fill="#E59400" />
      {/* Package held — tiny box */}
      <rect x="13" y="7" width="3" height="3" fill="#FFAA00" />
      <rect x="14" y="8" width="1" height="1" fill="#E59400" />
    </svg>
  );
}
