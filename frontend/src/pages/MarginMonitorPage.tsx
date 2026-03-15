import { useMemo, useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { KpiCard } from "@/components/KpiCard";
import { MarginTrendChart } from "@/components/MarginTrendChart";
import { useSkus } from "@/api/skus";
import { useMarginHistory } from "@/api/capital";
import { usePortfolioSummary } from "@/api/portfolio";
import { formatPercent } from "@/lib/formatters";

const PERIOD_OPTIONS = [
  { value: "30", label: "30 days" },
  { value: "60", label: "60 days" },
  { value: "90", label: "90 days" },
] as const;

function daysAgo(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return d.toISOString().split("T")[0];
}

function today(): string {
  return new Date().toISOString().split("T")[0];
}

export default function MarginMonitorPage() {
  const [selectedSkuId, setSelectedSkuId] = useState<string>("");
  const [periodDays, setPeriodDays] = useState<string>("90");

  // Queries
  const { data: allSkus, isLoading: skusLoading } = useSkus();
  const { data: portfolioSummary, isLoading: summaryLoading } =
    usePortfolioSummary();

  const fromDate = useMemo(() => daysAgo(parseInt(periodDays, 10)), [periodDays]);
  const toDate = useMemo(() => today(), []);

  const {
    data: marginSnapshots,
    isLoading: marginLoading,
    isError: marginError,
  } = useMarginHistory(selectedSkuId, fromDate, toDate);

  // Transform margin snapshots into the chart format
  const chartData = useMemo(() => {
    if (!marginSnapshots) return [];
    return marginSnapshots.map((s) => ({
      date: s.snapshotDate,
      grossMargin: s.grossMarginPercent,
      netMargin: s.netMarginPercent,
    }));
  }, [marginSnapshots]);

  // Latest snapshot for refund/chargeback rates
  const latestSnapshot = marginSnapshots?.[marginSnapshots.length - 1];

  // Count SKUs above 30% net margin floor
  const skusAboveFloor = useMemo(() => {
    // We don't have per-SKU margin data in the SKU list itself,
    // so we use activeSkus from portfolio summary as a proxy
    return portfolioSummary?.activeSkus ?? 0;
  }, [portfolioSummary]);

  const totalActiveSkus = portfolioSummary?.activeSkus ?? 0;

  return (
    <div className="space-y-6">
      {/* Page title */}
      <h1
        style={{
          fontFamily: "'Bricolage Grotesque', sans-serif",
          fontWeight: 700,
          fontSize: 28,
          lineHeight: 1.2,
          color: "var(--text-primary)",
        }}
      >
        Margin Monitor
      </h1>

      {/* KPI Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {summaryLoading ? (
          <>
            <Skeleton className="h-24 rounded-lg" />
            <Skeleton className="h-24 rounded-lg" />
          </>
        ) : (
          <>
            <KpiCard
              label="Portfolio Blended Margin"
              value={formatPercent(portfolioSummary?.blendedNetMargin ?? 0)}
              accentColor={
                (portfolioSummary?.blendedNetMargin ?? 0) >= 30
                  ? "profit"
                  : "danger"
              }
            />
            <KpiCard
              label="SKUs Above Floor"
              value={`${skusAboveFloor} / ${totalActiveSkus}`}
              accentColor="info"
            />
          </>
        )}
      </div>

      {/* Selectors */}
      <div className="flex flex-wrap items-end gap-4">
        <div className="min-w-[220px]">
          <label
            style={{
              fontFamily: "'Onest', sans-serif",
              fontWeight: 500,
              fontSize: 12,
              color: "var(--text-secondary)",
              display: "block",
              marginBottom: 4,
            }}
          >
            SKU
          </label>
          {skusLoading ? (
            <Skeleton className="h-8 w-full" />
          ) : (
            <Select
              value={selectedSkuId}
              onValueChange={(val) => setSelectedSkuId(val as string)}
            >
              <SelectTrigger
                className="w-full"
                style={{ backgroundColor: "var(--bg-surface-2)" }}
              >
                <SelectValue placeholder="Select a SKU..." />
              </SelectTrigger>
              <SelectContent>
                {allSkus?.map((sku) => (
                  <SelectItem key={sku.id} value={sku.id}>
                    {sku.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          )}
        </div>

        <div className="min-w-[140px]">
          <label
            style={{
              fontFamily: "'Onest', sans-serif",
              fontWeight: 500,
              fontSize: 12,
              color: "var(--text-secondary)",
              display: "block",
              marginBottom: 4,
            }}
          >
            Period
          </label>
          <Select
            value={periodDays}
            onValueChange={(val) => setPeriodDays(val as string)}
          >
            <SelectTrigger
              className="w-full"
              style={{ backgroundColor: "var(--bg-surface-2)" }}
            >
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {PERIOD_OPTIONS.map((opt) => (
                <SelectItem key={opt.value} value={opt.value}>
                  {opt.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </div>

      {/* Chart */}
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
            Margin Trend
            {selectedSkuId && allSkus && (
              <span
                className="ml-3"
                style={{
                  fontFamily: "'Onest', sans-serif",
                  fontWeight: 400,
                  fontSize: 14,
                  color: "var(--text-secondary)",
                }}
              >
                {allSkus.find((s) => s.id === selectedSkuId)?.name ?? ""}
              </span>
            )}
          </CardTitle>
        </CardHeader>
        <CardContent>
          {!selectedSkuId ? (
            <div
              className="flex items-center justify-center py-16"
              style={{
                fontFamily: "'Onest', sans-serif",
                fontSize: 14,
                color: "var(--text-tertiary)",
              }}
            >
              Select a SKU above to view margin trends.
            </div>
          ) : marginLoading ? (
            <Skeleton className="h-[300px] w-full rounded-lg" />
          ) : marginError ? (
            <p
              style={{
                fontFamily: "'Onest', sans-serif",
                fontSize: 14,
                color: "var(--danger)",
                textAlign: "center",
                padding: "48px 0",
              }}
            >
              Failed to load margin history. Please try again.
            </p>
          ) : chartData.length === 0 ? (
            <div
              className="flex items-center justify-center py-16"
              style={{
                fontFamily: "'Onest', sans-serif",
                fontSize: 14,
                color: "var(--text-tertiary)",
              }}
            >
              No margin data available for this period.
            </div>
          ) : (
            <MarginTrendChart data={chartData} height={320} />
          )}
        </CardContent>
      </Card>

      {/* Refund & Chargeback rates */}
      {selectedSkuId && latestSnapshot && (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <RateCard
            label="Refund Rate"
            value={latestSnapshot.refundRate}
            threshold={5}
          />
          <RateCard
            label="Chargeback Rate"
            value={latestSnapshot.chargebackRate}
            threshold={2}
          />
        </div>
      )}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────
// Rate card (refund / chargeback)
// ─────────────────────────────────────────────────────────────────
function RateCard({
  label,
  value,
  threshold,
}: {
  label: string;
  value: number;
  threshold: number;
}) {
  const isAboveThreshold = value > threshold;
  const accentColor = isAboveThreshold ? "var(--danger)" : "var(--profit)";

  return (
    <div
      className="rounded-lg p-4"
      style={{
        backgroundColor: "var(--bg-surface-1)",
        border: "1px solid var(--border-default)",
        borderLeft: `3px solid ${accentColor}`,
      }}
    >
      <div
        style={{
          fontFamily: "'Onest', sans-serif",
          fontWeight: 500,
          fontSize: 12,
          textTransform: "uppercase",
          color: "var(--text-secondary)",
          marginBottom: 4,
        }}
      >
        {label}
      </div>
      <div className="flex items-baseline gap-2">
        <span
          style={{
            fontFamily: "'Martian Mono', monospace",
            fontWeight: 500,
            fontSize: 28,
            color: accentColor,
          }}
        >
          {formatPercent(value)}
        </span>
        <span
          style={{
            fontFamily: "'Onest', sans-serif",
            fontSize: 12,
            color: "var(--text-tertiary)",
          }}
        >
          threshold: {threshold}%
        </span>
      </div>
      {isAboveThreshold && (
        <div
          className="mt-2 rounded-md px-3 py-1.5"
          style={{
            backgroundColor: "var(--danger-dim)",
            fontFamily: "'Onest', sans-serif",
            fontSize: 12,
            color: "var(--danger)",
          }}
        >
          Above threshold — review required
        </div>
      )}
    </div>
  );
}
