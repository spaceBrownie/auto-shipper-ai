import { useNavigate } from "react-router-dom";
import { SpriteScene } from "@/components/sprites/SpriteScene";
import { Scout } from "@/components/sprites/Scout";
import { Button } from "@/components/ui/button";

export default function DemandSignalsPage() {
  const navigate = useNavigate();

  return (
    <div>
      <h1
        className="mb-8"
        style={{
          fontFamily: "'Bricolage Grotesque', sans-serif",
          fontWeight: 700,
          fontSize: 28,
          lineHeight: 1.2,
          color: "var(--text-primary)",
        }}
      >
        Demand Signals
      </h1>

      <div className="flex flex-col items-center justify-center py-24">
        <SpriteScene
          message="DemandScanJob isn't wired up yet. Coming soon!"
          animation="idle"
        >
          <Scout size={48} />
        </SpriteScene>

        <p
          className="mt-8 max-w-md text-center"
          style={{
            fontFamily: "'Onest', sans-serif",
            fontSize: 14,
            lineHeight: 1.6,
            color: "var(--text-secondary)",
          }}
        >
          This view will show trending categories discovered from Google Trends,
          Reddit, and Amazon PA-API. The DemandScanJob will automatically
          surface high-potential product opportunities with validated demand
          signals.
        </p>

        <Button
          className="mt-6"
          onClick={() => navigate("/experiments")}
          style={{
            backgroundColor: "var(--accent)",
            color: "var(--bg-root)",
            fontFamily: "'Onest', sans-serif",
            fontWeight: 600,
          }}
        >
          Create an experiment manually
        </Button>
      </div>
    </div>
  );
}
