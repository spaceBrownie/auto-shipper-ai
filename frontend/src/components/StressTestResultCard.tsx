import type { StressTestResponse } from "@/api/types";
import { Check, X } from "lucide-react";

interface StressTestResultCardProps {
  result: StressTestResponse;
}

interface ScenarioCard {
  label: string;
  amount: number;
}

export function StressTestResultCard({ result }: StressTestResultCardProps) {
  const passed = result.passed;
  const headerColor = passed ? "var(--profit)" : "var(--danger)";

  const formatCurrency = (amount: number) =>
    new Intl.NumberFormat("en-US", {
      style: "currency",
      currency: result.currency || "USD",
      minimumFractionDigits: 2,
    }).format(amount);

  const scenarios: ScenarioCard[] = [
    { label: "2x Shipping", amount: result.stressedShipping },
    { label: "+15% CAC", amount: result.stressedCac },
    { label: "+10% Supplier", amount: result.stressedSupplier },
    { label: "5% Refund", amount: result.stressedRefund },
    { label: "2% Chargeback", amount: result.stressedChargeback },
  ];

  return (
    <div
      className="rounded-lg overflow-hidden"
      style={{
        backgroundColor: "var(--bg-surface-1)",
        border: "1px solid var(--border-default)",
      }}
    >
      {/* Header bar */}
      <div
        className="flex items-center gap-2 px-5 py-3"
        style={{ backgroundColor: passed ? "var(--profit-dim)" : "var(--danger-dim)" }}
      >
        {passed ? (
          <Check size={18} style={{ color: headerColor }} />
        ) : (
          <X size={18} style={{ color: headerColor }} />
        )}
        <span
          style={{
            fontFamily: "'Bricolage Grotesque', sans-serif",
            fontWeight: 700,
            fontSize: 16,
            color: headerColor,
            textTransform: "uppercase",
          }}
        >
          Stress Test {passed ? "Passed" : "Failed"}
        </span>
      </div>

      {/* Metrics row */}
      <div className="px-5 py-4">
        <div className="grid grid-cols-4 gap-4 mb-5">
          {[
            { label: "Gross Margin", value: `${result.grossMarginPercent.toFixed(1)}%` },
            { label: "Net Margin", value: `${result.netMarginPercent.toFixed(1)}%` },
            { label: "Stressed Cost", value: formatCurrency(result.stressedTotalCost) },
            { label: "Est. Price", value: formatCurrency(result.estimatedPrice) },
          ].map((metric) => (
            <div key={metric.label}>
              <div
                style={{
                  fontFamily: "'Onest', sans-serif",
                  fontWeight: 500,
                  fontSize: 12,
                  color: "var(--text-secondary)",
                  textTransform: "uppercase",
                  marginBottom: 4,
                }}
              >
                {metric.label}
              </div>
              <div
                style={{
                  fontFamily: "'Martian Mono', monospace",
                  fontWeight: 500,
                  fontSize: 20,
                  color: "var(--text-primary)",
                }}
              >
                {metric.value}
              </div>
            </div>
          ))}
        </div>

        {/* Scenario mini cards */}
        <div className="grid grid-cols-5 gap-2">
          {scenarios.map((s) => (
            <div
              key={s.label}
              className="rounded-md px-3 py-2 text-center"
              style={{
                backgroundColor: "var(--bg-surface-2)",
                border: "1px solid var(--border-default)",
              }}
            >
              <div
                style={{
                  fontFamily: "'Onest', sans-serif",
                  fontWeight: 500,
                  fontSize: 11,
                  color: "var(--text-secondary)",
                  marginBottom: 4,
                }}
              >
                {s.label}
              </div>
              <div
                style={{
                  fontFamily: "'Martian Mono', monospace",
                  fontWeight: 400,
                  fontSize: 13,
                  color: "var(--text-primary)",
                }}
              >
                {formatCurrency(s.amount)}
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
