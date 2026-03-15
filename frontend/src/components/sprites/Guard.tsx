interface GuardProps {
  size?: number;
}

// Pixel-art sootball with a shield — bold blue, stern pixel brows
// The compliance gatekeeper
export function Guard({ size = 32 }: GuardProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      style={{ imageRendering: "pixelated" }}
    >
      {/* Sootball body — slightly wider, more imposing */}
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
      {/* Angry pixel brows — V shape */}
      <rect x="3" y="3" width="2" height="1" fill="#60a5fa" />
      <rect x="5" y="4" width="1" height="1" fill="#60a5fa" />
      <rect x="11" y="3" width="2" height="1" fill="#60a5fa" />
      <rect x="10" y="4" width="1" height="1" fill="#60a5fa" />
      {/* Big stern eyes */}
      <rect x="4" y="5" width="2" height="2" fill="#FFFFFF" />
      <rect x="10" y="5" width="2" height="2" fill="#FFFFFF" />
      <rect x="5" y="6" width="1" height="1" fill="#60a5fa" />
      <rect x="10" y="6" width="1" height="1" fill="#60a5fa" />
      {/* Flat mouth — no nonsense */}
      <rect x="6" y="8" width="4" height="1" fill="#60a5fa" />
      {/* Tiny legs */}
      <rect x="4" y="11" width="2" height="2" fill="#60a5fa" />
      <rect x="10" y="11" width="2" height="2" fill="#60a5fa" />
      <rect x="4" y="13" width="2" height="1" fill="#3b82f6" />
      <rect x="10" y="13" width="2" height="1" fill="#3b82f6" />
      {/* Shield — pixel art */}
      <rect x="0" y="4" width="1" height="1" fill="#60a5fa" />
      <rect x="0" y="5" width="1" height="4" fill="#3b82f6" />
      <rect x="0" y="9" width="1" height="1" fill="#60a5fa" />
      <rect x="0" y="5" width="1" height="1" fill="#FFFFFF" />
    </svg>
  );
}
