import { useState } from "react";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { StatusBadge } from "@/components/StatusBadge";
import { VendorScoreBar } from "@/components/VendorScoreBar";
import { SpriteScene } from "@/components/sprites/SpriteScene";
import { Shipper } from "@/components/sprites/Shipper";
import { useVendors, useComputeVendorScore } from "@/api/vendors";
import { formatDate } from "@/lib/formatters";
import type { VendorResponse, VendorScoreResponse } from "@/api/types";
import { Check, X, ChevronDown, ChevronUp } from "lucide-react";

export default function VendorScorecardPage() {
  const { data: vendors, isLoading, isError } = useVendors();
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [scores, setScores] = useState<Record<string, VendorScoreResponse>>({});

  // Score computation form state (per-vendor, but we only show one at a time)
  const [scoreForm, setScoreForm] = useState({
    onTimeRate: "95",
    defectRate: "2",
    breachCount: "0",
    avgResponseTimeHours: "24",
  });

  const computeScoreMutation = useComputeVendorScore();

  const toggleExpanded = (id: string) => {
    setExpandedId(expandedId === id ? null : id);
  };

  const handleComputeScore = async (vendorId: string) => {
    const result = await computeScoreMutation.mutateAsync({
      id: vendorId,
      onTimeRate: parseFloat(scoreForm.onTimeRate) || 0,
      defectRate: parseFloat(scoreForm.defectRate) || 0,
      breachCount: parseInt(scoreForm.breachCount, 10) || 0,
      avgResponseTimeHours: parseFloat(scoreForm.avgResponseTimeHours) || 0,
    });
    setScores((prev) => ({ ...prev, [vendorId]: result }));
  };

  // ─────────────────────────────────────────────────────────────
  // Loading state
  // ─────────────────────────────────────────────────────────────
  if (isLoading) {
    return (
      <div className="space-y-6">
        <h1
          style={{
            fontFamily: "'Bricolage Grotesque', sans-serif",
            fontWeight: 700,
            fontSize: 28,
            lineHeight: 1.2,
            color: "var(--text-primary)",
          }}
        >
          Vendor Scorecard
        </h1>
        <div className="space-y-3">
          {Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} className="h-12 w-full rounded-lg" />
          ))}
        </div>
      </div>
    );
  }

  // ─────────────────────────────────────────────────────────────
  // Error state
  // ─────────────────────────────────────────────────────────────
  if (isError) {
    return (
      <div className="space-y-6">
        <h1
          style={{
            fontFamily: "'Bricolage Grotesque', sans-serif",
            fontWeight: 700,
            fontSize: 28,
            color: "var(--text-primary)",
          }}
        >
          Vendor Scorecard
        </h1>
        <p
          style={{
            fontFamily: "'Onest', sans-serif",
            fontSize: 14,
            color: "var(--danger)",
          }}
        >
          Failed to load vendors. Please try again.
        </p>
      </div>
    );
  }

  // ─────────────────────────────────────────────────────────────
  // Empty state
  // ─────────────────────────────────────────────────────────────
  if (!vendors || vendors.length === 0) {
    return (
      <div className="space-y-6">
        <h1
          style={{
            fontFamily: "'Bricolage Grotesque', sans-serif",
            fontWeight: 700,
            fontSize: 28,
            color: "var(--text-primary)",
          }}
        >
          Vendor Scorecard
        </h1>
        <div className="flex flex-col items-center justify-center py-20">
          <SpriteScene
            message="No vendors registered yet."
            animation="idle"
          >
            <Shipper size={48} />
          </SpriteScene>
        </div>
      </div>
    );
  }

  // ─────────────────────────────────────────────────────────────
  // Data
  // ─────────────────────────────────────────────────────────────
  return (
    <div className="space-y-6">
      <h1
        style={{
          fontFamily: "'Bricolage Grotesque', sans-serif",
          fontWeight: 700,
          fontSize: 28,
          lineHeight: 1.2,
          color: "var(--text-primary)",
        }}
      >
        Vendor Scorecard
      </h1>

      {/* Table */}
      <div
        className="rounded-lg overflow-hidden"
        style={{
          backgroundColor: "var(--bg-surface-1)",
          border: "1px solid var(--border-default)",
        }}
      >
        <table className="w-full">
          <thead>
            <tr style={{ borderBottom: "1px solid var(--border-default)" }}>
              {["Name", "Status", "Email", "SLA Confirmed", "Created", ""].map(
                (header) => (
                  <th
                    key={header}
                    className="text-left px-4 py-3"
                    style={{
                      fontFamily: "'Onest', sans-serif",
                      fontWeight: 600,
                      fontSize: 12,
                      textTransform: "uppercase",
                      color: "var(--text-tertiary)",
                    }}
                  >
                    {header}
                  </th>
                ),
              )}
            </tr>
          </thead>
          <tbody>
            {vendors.map((vendor: VendorResponse) => {
              const isExpanded = expandedId === vendor.id;
              const cachedScore = scores[vendor.id];

              return (
                <VendorRow
                  key={vendor.id}
                  vendor={vendor}
                  isExpanded={isExpanded}
                  cachedScore={cachedScore}
                  scoreForm={scoreForm}
                  onScoreFormChange={setScoreForm}
                  onToggle={() => toggleExpanded(vendor.id)}
                  onComputeScore={() => handleComputeScore(vendor.id)}
                  isComputing={computeScoreMutation.isPending}
                />
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────
// Vendor row + expandable detail
// ─────────────────────────────────────────────────────────────────
function VendorRow({
  vendor,
  isExpanded,
  cachedScore,
  scoreForm,
  onScoreFormChange,
  onToggle,
  onComputeScore,
  isComputing,
}: {
  vendor: VendorResponse;
  isExpanded: boolean;
  cachedScore: VendorScoreResponse | undefined;
  scoreForm: {
    onTimeRate: string;
    defectRate: string;
    breachCount: string;
    avgResponseTimeHours: string;
  };
  onScoreFormChange: (form: typeof scoreForm) => void;
  onToggle: () => void;
  onComputeScore: () => void;
  isComputing: boolean;
}) {
  return (
    <>
      <tr
        onClick={onToggle}
        className="cursor-pointer"
        style={{
          borderBottom: "1px solid var(--border-default)",
          transition: "background-color 150ms",
        }}
        onMouseEnter={(e) =>
          (e.currentTarget.style.backgroundColor = "var(--bg-surface-3)")
        }
        onMouseLeave={(e) =>
          (e.currentTarget.style.backgroundColor = "transparent")
        }
      >
        <td
          className="px-4 py-3"
          style={{
            fontFamily: "'Onest', sans-serif",
            fontWeight: 500,
            fontSize: 14,
            color: "var(--text-primary)",
          }}
        >
          {vendor.name}
        </td>
        <td className="px-4 py-3">
          <StatusBadge status={vendor.status} />
        </td>
        <td
          className="px-4 py-3"
          style={{
            fontFamily: "'Onest', sans-serif",
            fontWeight: 400,
            fontSize: 14,
            color: "var(--text-secondary)",
          }}
        >
          {vendor.contactEmail}
        </td>
        <td className="px-4 py-3 text-center">
          {vendor.checklist.slaConfirmed ? (
            <Check
              size={16}
              style={{ color: "var(--profit)", display: "inline" }}
            />
          ) : (
            <X
              size={16}
              style={{ color: "var(--danger)", display: "inline" }}
            />
          )}
        </td>
        <td
          className="px-4 py-3"
          style={{
            fontFamily: "'Martian Mono', monospace",
            fontWeight: 400,
            fontSize: 12,
            color: "var(--text-secondary)",
          }}
        >
          {formatDate(vendor.createdAt)}
        </td>
        <td className="px-4 py-3">
          {isExpanded ? (
            <ChevronUp
              size={16}
              style={{ color: "var(--text-secondary)" }}
            />
          ) : (
            <ChevronDown
              size={16}
              style={{ color: "var(--text-secondary)" }}
            />
          )}
        </td>
      </tr>

      {/* Expanded detail panel */}
      {isExpanded && (
        <tr>
          <td
            colSpan={6}
            className="px-6 py-5"
            style={{
              backgroundColor: "var(--bg-surface-2)",
              borderBottom: "1px solid var(--border-default)",
            }}
          >
            <div className="space-y-5">
              {/* Score bar (if computed) */}
              {cachedScore && (
                <div className="space-y-4">
                  <VendorScoreBar
                    score={cachedScore.overallScore}
                    label="Overall Score"
                  />

                  {/* Score breakdown */}
                  <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                    <ScoreMetric
                      label="On-Time Rate"
                      value={`${cachedScore.onTimeRate.toFixed(1)}%`}
                    />
                    <ScoreMetric
                      label="Defect Rate"
                      value={`${cachedScore.defectRate.toFixed(1)}%`}
                    />
                    <ScoreMetric
                      label="Breach Count"
                      value={String(cachedScore.breachCount)}
                    />
                    <ScoreMetric
                      label="Avg Response Time"
                      value={`${cachedScore.avgResponseTimeHours.toFixed(1)}h`}
                    />
                  </div>
                </div>
              )}

              {/* Score computation form */}
              {!cachedScore && (
                <div className="space-y-3">
                  <p
                    style={{
                      fontFamily: "'Onest', sans-serif",
                      fontSize: 13,
                      color: "var(--text-secondary)",
                    }}
                  >
                    Enter vendor performance data to compute a reliability
                    score.
                  </p>
                  <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                    <div>
                      <Label
                        style={{
                          fontFamily: "'Onest', sans-serif",
                          fontWeight: 500,
                          fontSize: 11,
                          color: "var(--text-tertiary)",
                        }}
                      >
                        On-Time Rate (%)
                      </Label>
                      <Input
                        type="number"
                        step="0.1"
                        value={scoreForm.onTimeRate}
                        onChange={(e) =>
                          onScoreFormChange({
                            ...scoreForm,
                            onTimeRate: e.target.value,
                          })
                        }
                        style={{
                          backgroundColor: "var(--bg-surface-3)",
                          fontFamily: "'Martian Mono', monospace",
                          fontSize: 12,
                        }}
                      />
                    </div>
                    <div>
                      <Label
                        style={{
                          fontFamily: "'Onest', sans-serif",
                          fontWeight: 500,
                          fontSize: 11,
                          color: "var(--text-tertiary)",
                        }}
                      >
                        Defect Rate (%)
                      </Label>
                      <Input
                        type="number"
                        step="0.1"
                        value={scoreForm.defectRate}
                        onChange={(e) =>
                          onScoreFormChange({
                            ...scoreForm,
                            defectRate: e.target.value,
                          })
                        }
                        style={{
                          backgroundColor: "var(--bg-surface-3)",
                          fontFamily: "'Martian Mono', monospace",
                          fontSize: 12,
                        }}
                      />
                    </div>
                    <div>
                      <Label
                        style={{
                          fontFamily: "'Onest', sans-serif",
                          fontWeight: 500,
                          fontSize: 11,
                          color: "var(--text-tertiary)",
                        }}
                      >
                        Breach Count
                      </Label>
                      <Input
                        type="number"
                        step="1"
                        min="0"
                        value={scoreForm.breachCount}
                        onChange={(e) =>
                          onScoreFormChange({
                            ...scoreForm,
                            breachCount: e.target.value,
                          })
                        }
                        style={{
                          backgroundColor: "var(--bg-surface-3)",
                          fontFamily: "'Martian Mono', monospace",
                          fontSize: 12,
                        }}
                      />
                    </div>
                    <div>
                      <Label
                        style={{
                          fontFamily: "'Onest', sans-serif",
                          fontWeight: 500,
                          fontSize: 11,
                          color: "var(--text-tertiary)",
                        }}
                      >
                        Avg Response (hours)
                      </Label>
                      <Input
                        type="number"
                        step="0.5"
                        value={scoreForm.avgResponseTimeHours}
                        onChange={(e) =>
                          onScoreFormChange({
                            ...scoreForm,
                            avgResponseTimeHours: e.target.value,
                          })
                        }
                        style={{
                          backgroundColor: "var(--bg-surface-3)",
                          fontFamily: "'Martian Mono', monospace",
                          fontSize: 12,
                        }}
                      />
                    </div>
                  </div>
                  <Button
                    onClick={onComputeScore}
                    disabled={isComputing}
                    style={{
                      backgroundColor: "var(--accent)",
                      color: "var(--bg-root)",
                      fontFamily: "'Onest', sans-serif",
                      fontWeight: 600,
                    }}
                  >
                    {isComputing ? "Computing..." : "Compute Score"}
                  </Button>
                </div>
              )}
            </div>
          </td>
        </tr>
      )}
    </>
  );
}

// ─────────────────────────────────────────────────────────────────
// Score metric mini card
// ─────────────────────────────────────────────────────────────────
function ScoreMetric({ label, value }: { label: string; value: string }) {
  return (
    <div
      className="rounded-md px-3 py-2"
      style={{
        backgroundColor: "var(--bg-surface-3)",
        border: "1px solid var(--border-default)",
      }}
    >
      <div
        style={{
          fontFamily: "'Onest', sans-serif",
          fontWeight: 500,
          fontSize: 11,
          color: "var(--text-tertiary)",
          textTransform: "uppercase",
          marginBottom: 2,
        }}
      >
        {label}
      </div>
      <div
        style={{
          fontFamily: "'Martian Mono', monospace",
          fontWeight: 500,
          fontSize: 16,
          color: "var(--text-primary)",
        }}
      >
        {value}
      </div>
    </div>
  );
}
