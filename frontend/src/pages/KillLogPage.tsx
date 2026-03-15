import { useMemo } from "react";
import { useSkus } from "@/api/skus";
import { useKillRecommendations, useConfirmKill } from "@/api/portfolio";
import { KpiCard } from "@/components/KpiCard";
import { DataTable, type Column } from "@/components/DataTable";

import { SpriteScene } from "@/components/sprites/SpriteScene";
import { Reaper } from "@/components/sprites/Reaper";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { formatDate } from "@/lib/formatters";
import type { SkuResponse, KillRecommendationResponse } from "@/api/types";

export default function KillLogPage() {
  const { data: terminatedSkus, isLoading: skusLoading } =
    useSkus("Terminated");
  const { data: killRecs, isLoading: killRecsLoading } =
    useKillRecommendations();
  const confirmKill = useConfirmKill();

  // Reason breakdown
  const reasonBreakdown = useMemo(() => {
    if (!terminatedSkus) return [];
    const counts: Record<string, number> = {};
    for (const sku of terminatedSkus) {
      const reason = sku.terminationReason || "Unknown";
      counts[reason] = (counts[reason] || 0) + 1;
    }
    return Object.entries(counts).sort((a, b) => b[1] - a[1]);
  }, [terminatedSkus]);

  const isEmpty =
    !skusLoading &&
    !killRecsLoading &&
    (!terminatedSkus || terminatedSkus.length === 0) &&
    (!killRecs || killRecs.length === 0);

  const terminatedColumns: Column<SkuResponse>[] = [
    { key: "name", header: "Name" },
    {
      key: "terminationReason",
      header: "Reason",
      render: (v: string | null) => (
        <span
          style={{
            fontFamily: "'Martian Mono', monospace",
            fontSize: 12,
            color: "var(--danger)",
          }}
        >
          {v || "Unknown"}
        </span>
      ),
    },
    {
      key: "createdAt",
      header: "Created",
      render: (v: string) => (
        <span
          style={{
            fontFamily: "'Martian Mono', monospace",
            fontSize: 12,
          }}
        >
          {formatDate(v)}
        </span>
      ),
    },
  ];

  const killRecColumns: Column<KillRecommendationResponse>[] = [
    {
      key: "skuId",
      header: "SKU ID",
      render: (v: string) => (
        <span
          style={{
            fontFamily: "'Martian Mono', monospace",
            fontSize: 12,
          }}
        >
          {v.length > 8 ? v.slice(0, 8) + "..." : v}
        </span>
      ),
    },
    {
      key: "daysNegative",
      header: "Days Neg",
      render: (v: number) => (
        <span
          style={{
            fontFamily: "'Martian Mono', monospace",
            color: v >= 7 ? "var(--danger)" : "var(--warning)",
          }}
        >
          {v}
        </span>
      ),
    },
    {
      key: "avgNetMargin",
      header: "Avg Margin",
      render: (v: number) => (
        <span
          style={{
            fontFamily: "'Martian Mono', monospace",
            color: "var(--danger)",
          }}
        >
          {v.toFixed(1)}%
        </span>
      ),
    },
    {
      key: "detectedAt",
      header: "Detected",
      render: (v: string) => (
        <span
          style={{
            fontFamily: "'Martian Mono', monospace",
            fontSize: 12,
          }}
        >
          {formatDate(v)}
        </span>
      ),
    },
    {
      key: "confirmedAt",
      header: "Status",
      render: (v: string | null, row: KillRecommendationResponse) => {
        if (v) {
          return (
            <span
              style={{
                fontFamily: "'Martian Mono', monospace",
                fontSize: 12,
                color: "var(--text-secondary)",
              }}
            >
              Confirmed {formatDate(v)}
            </span>
          );
        }
        return (
          <Button
            size="sm"
            variant="outline"
            onClick={(e) => {
              e.stopPropagation();
              confirmKill.mutate(row.id);
            }}
            disabled={confirmKill.isPending}
            style={{
              borderColor: "var(--danger)",
              color: "var(--danger)",
              fontSize: 12,
            }}
          >
            Confirm Kill
          </Button>
        );
      },
    },
  ];

  if (isEmpty) {
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
          Kill Log
        </h1>
        <div className="flex flex-col items-center justify-center py-24">
          <SpriteScene
            message="Nothing terminated. The engine is thriving."
            animation="idle"
          >
            <Reaper size={48} />
          </SpriteScene>
        </div>
      </div>
    );
  }

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
        Kill Log
      </h1>

      {/* KPI + Reason Breakdown */}
      <div className="grid grid-cols-1 gap-4 mb-6 md:grid-cols-2">
        {skusLoading ? (
          <Skeleton
            className="h-24"
            style={{ backgroundColor: "var(--bg-surface-3)" }}
          />
        ) : (
          <KpiCard
            label="Total Terminated"
            value={terminatedSkus?.length ?? 0}
            accentColor="danger"
          />
        )}

        {/* Reason Breakdown */}
        <Card
          style={{
            backgroundColor: "var(--bg-surface-1)",
            border: "1px solid var(--border-default)",
            borderLeft: "3px solid var(--danger)",
          }}
        >
          <CardHeader className="pb-2 pt-4 px-4">
            <CardTitle
              style={{
                fontFamily: "'Onest', sans-serif",
                fontWeight: 500,
                fontSize: 12,
                textTransform: "uppercase",
                color: "var(--text-secondary)",
                letterSpacing: "0.03em",
              }}
            >
              By Reason
            </CardTitle>
          </CardHeader>
          <CardContent className="px-4 pb-4">
            {reasonBreakdown.length === 0 ? (
              <span
                style={{
                  fontFamily: "'Onest', sans-serif",
                  color: "var(--text-tertiary)",
                  fontSize: 13,
                }}
              >
                No data
              </span>
            ) : (
              <div className="flex flex-col gap-1">
                {reasonBreakdown.map(([reason, count]) => (
                  <div
                    key={reason}
                    className="flex items-center justify-between"
                  >
                    <span
                      style={{
                        fontFamily: "'Onest', sans-serif",
                        fontSize: 13,
                        color: "var(--text-primary)",
                      }}
                    >
                      {reason}
                    </span>
                    <span
                      style={{
                        fontFamily: "'Martian Mono', monospace",
                        fontSize: 13,
                        color: "var(--danger)",
                      }}
                    >
                      {count}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      {/* Terminated SKUs Table */}
      <Card
        className="mb-6"
        style={{
          backgroundColor: "var(--bg-surface-1)",
          border: "1px solid var(--border-default)",
        }}
      >
        <CardHeader>
          <CardTitle
            style={{
              fontFamily: "'Bricolage Grotesque', sans-serif",
              fontWeight: 600,
              fontSize: 20,
              color: "var(--text-primary)",
            }}
          >
            Terminated SKUs
          </CardTitle>
        </CardHeader>
        <CardContent>
          {skusLoading ? (
            <div className="flex flex-col gap-2">
              <Skeleton
                className="h-10 w-full"
                style={{ backgroundColor: "var(--bg-surface-3)" }}
              />
              <Skeleton
                className="h-10 w-full"
                style={{ backgroundColor: "var(--bg-surface-3)" }}
              />
            </div>
          ) : terminatedSkus && terminatedSkus.length > 0 ? (
            <DataTable columns={terminatedColumns} data={terminatedSkus} />
          ) : (
            <span
              style={{
                fontFamily: "'Onest', sans-serif",
                color: "var(--text-secondary)",
                fontSize: 14,
              }}
            >
              No terminated SKUs.
            </span>
          )}
        </CardContent>
      </Card>

      {/* Kill Recommendations */}
      <Card
        style={{
          backgroundColor: "var(--bg-surface-1)",
          border: "1px solid var(--border-default)",
        }}
      >
        <CardHeader>
          <CardTitle
            style={{
              fontFamily: "'Bricolage Grotesque', sans-serif",
              fontWeight: 600,
              fontSize: 20,
              color: "var(--text-primary)",
            }}
          >
            Pending Kill Recommendations
          </CardTitle>
        </CardHeader>
        <CardContent>
          {killRecsLoading ? (
            <div className="flex flex-col gap-2">
              <Skeleton
                className="h-10 w-full"
                style={{ backgroundColor: "var(--bg-surface-3)" }}
              />
              <Skeleton
                className="h-10 w-full"
                style={{ backgroundColor: "var(--bg-surface-3)" }}
              />
            </div>
          ) : killRecs && killRecs.length > 0 ? (
            <DataTable columns={killRecColumns} data={killRecs} />
          ) : (
            <span
              style={{
                fontFamily: "'Onest', sans-serif",
                color: "var(--text-secondary)",
                fontSize: 14,
              }}
            >
              No kill recommendations.
            </span>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
