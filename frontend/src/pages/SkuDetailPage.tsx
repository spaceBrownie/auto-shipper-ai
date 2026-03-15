import { useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { ArrowLeft } from "lucide-react";
import { useSku, useSkuStateHistory } from "@/api/skus";
import { useSkuPricing } from "@/api/pricing";
import { useSkuPnl } from "@/api/capital";
import { useComplianceStatus } from "@/api/compliance";
import type {
  SkuResponse,
  SkuStateHistoryEntry,
  PricingHistoryEntry,
  AuditEntry,
} from "@/api/types";
import { StatusBadge } from "@/components/StatusBadge";
import { KpiCard } from "@/components/KpiCard";
import { MarginTrendChart } from "@/components/MarginTrendChart";
import { DataTable, type Column } from "@/components/DataTable";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import {
  formatDate,
  formatDateTime,
  formatMoney,
  formatPercent,
} from "@/lib/formatters";

// ---------------------------------------------------------------------------
// Helper: compute default 90-day date range
// ---------------------------------------------------------------------------
function defaultDateRange(): { from: string; to: string } {
  const to = new Date();
  const from = new Date(to.getTime() - 90 * 24 * 60 * 60 * 1000);
  return {
    from: from.toISOString().slice(0, 10),
    to: to.toISOString().slice(0, 10),
  };
}

// ---------------------------------------------------------------------------
// Sub-components for each tab
// ---------------------------------------------------------------------------

function OverviewTab({
  sku,
  history,
  historyLoading,
}: {
  sku: SkuResponse;
  history: SkuStateHistoryEntry[] | undefined;
  historyLoading: boolean;
}) {
  return (
    <div className="grid grid-cols-2 gap-6 mt-4">
      {/* SKU info card */}
      <div
        className="rounded-lg p-5"
        style={{
          backgroundColor: "var(--bg-surface-1)",
          border: "1px solid var(--border-default)",
        }}
      >
        <h3
          style={{
            fontFamily: "'Bricolage Grotesque', sans-serif",
            fontWeight: 600,
            fontSize: 20,
            lineHeight: 1.3,
            color: "var(--text-primary)",
            marginBottom: 16,
          }}
        >
          SKU Information
        </h3>
        <div className="space-y-3">
          {[
            { label: "Name", value: sku.name, mono: false },
            { label: "Category", value: sku.category, mono: false },
            {
              label: "State",
              value: "",
              mono: false,
              render: <StatusBadge status={sku.currentState} />,
            },
            { label: "Created", value: formatDate(sku.createdAt), mono: true },
            { label: "Updated", value: formatDate(sku.updatedAt), mono: true },
          ].map((item) => (
            <div key={item.label} className="flex justify-between items-center">
              <span
                style={{
                  fontFamily: "'Onest', sans-serif",
                  fontWeight: 500,
                  fontSize: 12,
                  textTransform: "uppercase",
                  color: "var(--text-secondary)",
                }}
              >
                {item.label}
              </span>
              {item.render ?? (
                <span
                  style={{
                    fontFamily: item.mono
                      ? "'Martian Mono', monospace"
                      : "'Onest', sans-serif",
                    fontWeight: 400,
                    fontSize: 14,
                    color: "var(--text-primary)",
                  }}
                >
                  {item.value}
                </span>
              )}
            </div>
          ))}
          {sku.terminationReason && (
            <div className="flex justify-between items-center">
              <span
                style={{
                  fontFamily: "'Onest', sans-serif",
                  fontWeight: 500,
                  fontSize: 12,
                  textTransform: "uppercase",
                  color: "var(--text-secondary)",
                }}
              >
                Termination Reason
              </span>
              <span
                style={{
                  fontFamily: "'Onest', sans-serif",
                  fontWeight: 400,
                  fontSize: 14,
                  color: "var(--danger)",
                }}
              >
                {sku.terminationReason}
              </span>
            </div>
          )}
        </div>
      </div>

      {/* State history timeline */}
      <div
        className="rounded-lg p-5"
        style={{
          backgroundColor: "var(--bg-surface-1)",
          border: "1px solid var(--border-default)",
        }}
      >
        <h3
          style={{
            fontFamily: "'Bricolage Grotesque', sans-serif",
            fontWeight: 600,
            fontSize: 20,
            lineHeight: 1.3,
            color: "var(--text-primary)",
            marginBottom: 16,
          }}
        >
          State History
        </h3>
        {historyLoading ? (
          <div className="space-y-3">
            {Array.from({ length: 3 }).map((_, i) => (
              <Skeleton key={i} className="h-10 rounded" />
            ))}
          </div>
        ) : history && history.length > 0 ? (
          <div className="relative">
            {/* Vertical timeline line */}
            <div
              className="absolute left-[7px] top-2 bottom-2"
              style={{
                width: 2,
                backgroundColor: "var(--border-default)",
              }}
            />
            <div className="space-y-4">
              {history.map((entry, i) => (
                <div key={i} className="flex items-start gap-3 relative">
                  {/* Timeline dot */}
                  <div
                    className="flex-shrink-0 rounded-full mt-1.5"
                    style={{
                      width: 16,
                      height: 16,
                      backgroundColor: "var(--bg-surface-2)",
                      border: "2px solid var(--accent)",
                      zIndex: 1,
                    }}
                  />
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <StatusBadge status={entry.fromState} size="sm" />
                      <span
                        style={{
                          fontFamily: "'Onest', sans-serif",
                          fontSize: 12,
                          color: "var(--text-tertiary)",
                        }}
                      >
                        &rarr;
                      </span>
                      <StatusBadge status={entry.toState} size="sm" />
                    </div>
                    <span
                      className="block mt-1"
                      style={{
                        fontFamily: "'Martian Mono', monospace",
                        fontWeight: 400,
                        fontSize: 11,
                        color: "var(--text-tertiary)",
                      }}
                    >
                      {formatDateTime(entry.transitionedAt)}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          </div>
        ) : (
          <p
            style={{
              fontFamily: "'Onest', sans-serif",
              fontSize: 14,
              color: "var(--text-tertiary)",
            }}
          >
            No state transitions recorded yet.
          </p>
        )}
      </div>
    </div>
  );
}

function CostTab() {
  return (
    <div className="mt-4">
      <div
        className="rounded-lg p-6 flex flex-col items-center justify-center"
        style={{
          backgroundColor: "var(--bg-surface-1)",
          border: "1px solid var(--border-default)",
          minHeight: 200,
        }}
      >
        <p
          style={{
            fontFamily: "'Onest', sans-serif",
            fontSize: 14,
            color: "var(--text-secondary)",
            textAlign: "center",
            maxWidth: 400,
          }}
        >
          Cost envelope data is generated when the SKU passes through the Cost
          Gate Runner. Use the Cost Gate Runner page to verify costs and view the
          full 13-component breakdown.
        </p>
      </div>
    </div>
  );
}

function StressTestTab() {
  return (
    <div className="mt-4">
      <div
        className="rounded-lg p-6 flex flex-col items-center justify-center"
        style={{
          backgroundColor: "var(--bg-surface-1)",
          border: "1px solid var(--border-default)",
          minHeight: 200,
        }}
      >
        <p
          style={{
            fontFamily: "'Onest', sans-serif",
            fontSize: 14,
            color: "var(--text-secondary)",
            textAlign: "center",
            maxWidth: 400,
          }}
        >
          Stress test results are generated during the Cost Gate process. Use the
          Cost Gate Runner page to run the stress test and view pass/fail results
          with stressed cost scenarios.
        </p>
      </div>
    </div>
  );
}

function PricingTab({ skuId }: { skuId: string }) {
  const { data: pricing, isLoading } = useSkuPricing(skuId);

  if (isLoading) {
    return (
      <div className="mt-4 space-y-4">
        <div className="grid grid-cols-2 gap-4">
          <Skeleton className="h-24 rounded-lg" />
          <Skeleton className="h-24 rounded-lg" />
        </div>
        <Skeleton className="h-64 rounded-lg" />
      </div>
    );
  }

  if (!pricing) {
    return (
      <div className="mt-4">
        <div
          className="rounded-lg p-6 flex flex-col items-center justify-center"
          style={{
            backgroundColor: "var(--bg-surface-1)",
            border: "1px solid var(--border-default)",
            minHeight: 200,
          }}
        >
          <p
            style={{
              fontFamily: "'Onest', sans-serif",
              fontSize: 14,
              color: "var(--text-secondary)",
            }}
          >
            No pricing data available for this SKU yet.
          </p>
        </div>
      </div>
    );
  }

  const chartData = pricing.history.map((entry: PricingHistoryEntry) => ({
    date: entry.recordedAt,
    grossMargin: entry.marginPercent,
    netMargin: entry.marginPercent * 0.8,
  }));

  return (
    <div className="mt-4 space-y-6">
      {/* KPI row */}
      <div className="grid grid-cols-2 gap-4">
        <KpiCard
          label="Current Price"
          value={formatMoney(pricing.currentPrice, pricing.currency)}
          accentColor="accent"
        />
        <KpiCard
          label="Current Margin"
          value={formatPercent(pricing.currentMarginPercent)}
          accentColor={pricing.currentMarginPercent >= 30 ? "profit" : "danger"}
        />
      </div>

      {/* Pricing history chart */}
      {chartData.length > 0 && (
        <div
          className="rounded-lg p-5"
          style={{
            backgroundColor: "var(--bg-surface-1)",
            border: "1px solid var(--border-default)",
          }}
        >
          <h3
            style={{
              fontFamily: "'Bricolage Grotesque', sans-serif",
              fontWeight: 600,
              fontSize: 20,
              lineHeight: 1.3,
              color: "var(--text-primary)",
              marginBottom: 16,
            }}
          >
            Margin Trend
          </h3>
          <MarginTrendChart data={chartData} height={260} />
        </div>
      )}

      {/* Pricing history table */}
      {pricing.history.length > 0 && (
        <div
          className="rounded-lg overflow-hidden"
          style={{
            backgroundColor: "var(--bg-surface-1)",
            border: "1px solid var(--border-default)",
          }}
        >
          <div className="p-4 pb-0">
            <h3
              style={{
                fontFamily: "'Bricolage Grotesque', sans-serif",
                fontWeight: 600,
                fontSize: 20,
                lineHeight: 1.3,
                color: "var(--text-primary)",
                marginBottom: 12,
              }}
            >
              Price History
            </h3>
          </div>
          <DataTable
            columns={[
              {
                key: "recordedAt",
                header: "Date",
                render: (v: string) => (
                  <span
                    style={{
                      fontFamily: "'Martian Mono', monospace",
                      fontSize: 12,
                      color: "var(--text-secondary)",
                    }}
                  >
                    {formatDateTime(v)}
                  </span>
                ),
              },
              {
                key: "price",
                header: "Price",
                render: (v: number) => formatMoney(v, pricing.currency),
              },
              {
                key: "marginPercent",
                header: "Margin",
                render: (v: number) => formatPercent(v),
              },
              {
                key: "signalType",
                header: "Signal",
                render: (v: string) => (
                  <span style={{ fontSize: 12, color: "var(--text-secondary)" }}>
                    {v.replace(/_/g, " ")}
                  </span>
                ),
              },
              {
                key: "decisionType",
                header: "Decision",
                render: (v: string) => <StatusBadge status={v} size="sm" />,
              },
            ]}
            data={pricing.history}
          />
        </div>
      )}
    </div>
  );
}

function PnlTab({ skuId }: { skuId: string }) {
  const defaults = defaultDateRange();
  const [from, setFrom] = useState(defaults.from);
  const [to, setTo] = useState(defaults.to);

  const { data: pnl, isLoading } = useSkuPnl(skuId, from, to);

  return (
    <div className="mt-4 space-y-6">
      {/* Date range selector */}
      <div className="flex items-center gap-4">
        <div className="flex items-center gap-2">
          <label
            htmlFor="pnl-from"
            style={{
              fontFamily: "'Onest', sans-serif",
              fontWeight: 500,
              fontSize: 12,
              textTransform: "uppercase",
              color: "var(--text-secondary)",
            }}
          >
            From
          </label>
          <input
            id="pnl-from"
            type="date"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
            className="h-8 rounded-lg border px-2.5 py-1 text-sm outline-none"
            style={{
              backgroundColor: "var(--bg-surface-2)",
              borderColor: "var(--border-default)",
              color: "var(--text-primary)",
              fontFamily: "'Martian Mono', monospace",
              fontSize: 12,
            }}
          />
        </div>
        <div className="flex items-center gap-2">
          <label
            htmlFor="pnl-to"
            style={{
              fontFamily: "'Onest', sans-serif",
              fontWeight: 500,
              fontSize: 12,
              textTransform: "uppercase",
              color: "var(--text-secondary)",
            }}
          >
            To
          </label>
          <input
            id="pnl-to"
            type="date"
            value={to}
            onChange={(e) => setTo(e.target.value)}
            className="h-8 rounded-lg border px-2.5 py-1 text-sm outline-none"
            style={{
              backgroundColor: "var(--bg-surface-2)",
              borderColor: "var(--border-default)",
              color: "var(--text-primary)",
              fontFamily: "'Martian Mono', monospace",
              fontSize: 12,
            }}
          />
        </div>
      </div>

      {isLoading ? (
        <div className="grid grid-cols-4 gap-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} className="h-24 rounded-lg" />
          ))}
        </div>
      ) : pnl ? (
        <div className="grid grid-cols-4 gap-4">
          <KpiCard
            label="Revenue"
            value={formatMoney(pnl.totalRevenueAmount, pnl.totalRevenueCurrency)}
            accentColor="profit"
          />
          <KpiCard
            label="Cost"
            value={formatMoney(pnl.totalCostAmount, pnl.totalCostCurrency)}
            accentColor="danger"
          />
          <KpiCard
            label="Gross Margin"
            value={formatPercent(pnl.averageGrossMarginPercent)}
            accentColor={
              parseFloat(pnl.averageGrossMarginPercent) >= 50
                ? "profit"
                : "warning"
            }
          />
          <KpiCard
            label="Net Margin"
            value={formatPercent(pnl.averageNetMarginPercent)}
            accentColor={
              parseFloat(pnl.averageNetMarginPercent) >= 30
                ? "profit"
                : "danger"
            }
          />
        </div>
      ) : (
        <div
          className="rounded-lg p-6 flex flex-col items-center justify-center"
          style={{
            backgroundColor: "var(--bg-surface-1)",
            border: "1px solid var(--border-default)",
            minHeight: 200,
          }}
        >
          <p
            style={{
              fontFamily: "'Onest', sans-serif",
              fontSize: 14,
              color: "var(--text-secondary)",
            }}
          >
            No P&L data available for this SKU in the selected date range.
          </p>
        </div>
      )}

      {pnl && (
        <div
          className="rounded-lg p-4"
          style={{
            backgroundColor: "var(--bg-surface-1)",
            border: "1px solid var(--border-default)",
          }}
        >
          <p
            style={{
              fontFamily: "'Martian Mono', monospace",
              fontSize: 12,
              color: "var(--text-tertiary)",
            }}
          >
            Based on {pnl.snapshotCount} snapshot{pnl.snapshotCount !== 1 ? "s" : ""} from{" "}
            {formatDate(pnl.from)} to {formatDate(pnl.to)}
          </p>
        </div>
      )}
    </div>
  );
}

function ComplianceTab({ skuId }: { skuId: string }) {
  const { data: compliance, isLoading } = useComplianceStatus(skuId);

  if (isLoading) {
    return (
      <div className="mt-4 space-y-4">
        <Skeleton className="h-12 rounded-lg" />
        <Skeleton className="h-48 rounded-lg" />
      </div>
    );
  }

  if (!compliance) {
    return (
      <div className="mt-4">
        <div
          className="rounded-lg p-6 flex flex-col items-center justify-center"
          style={{
            backgroundColor: "var(--bg-surface-1)",
            border: "1px solid var(--border-default)",
            minHeight: 200,
          }}
        >
          <p
            style={{
              fontFamily: "'Onest', sans-serif",
              fontSize: 14,
              color: "var(--text-secondary)",
            }}
          >
            No compliance data available. Run a compliance check from the
            Compliance page.
          </p>
        </div>
      </div>
    );
  }

  const auditColumns: Column<AuditEntry>[] = [
    {
      key: "checkType",
      header: "Check Type",
      render: (v: string) => (
        <span
          style={{
            fontFamily: "'Onest', sans-serif",
            fontSize: 13,
            color: "var(--text-primary)",
          }}
        >
          {v.replace(/_/g, " ")}
        </span>
      ),
    },
    {
      key: "result",
      header: "Result",
      render: (v: string) => <StatusBadge status={v} size="sm" />,
    },
    {
      key: "reason",
      header: "Reason",
      render: (v: string | null) => (
        <span
          style={{
            fontFamily: "'Onest', sans-serif",
            fontSize: 13,
            color: v ? "var(--text-primary)" : "var(--text-tertiary)",
          }}
        >
          {v ?? "--"}
        </span>
      ),
    },
    {
      key: "detail",
      header: "Detail",
      render: (v: string | null) => (
        <span
          style={{
            fontFamily: "'Onest', sans-serif",
            fontSize: 12,
            color: v ? "var(--text-secondary)" : "var(--text-tertiary)",
            maxWidth: 200,
            display: "inline-block",
            overflow: "hidden",
            textOverflow: "ellipsis",
            whiteSpace: "nowrap",
          }}
          title={v ?? undefined}
        >
          {v ?? "--"}
        </span>
      ),
    },
    {
      key: "checkedAt",
      header: "Checked At",
      render: (v: string) => (
        <span
          style={{
            fontFamily: "'Martian Mono', monospace",
            fontSize: 11,
            color: "var(--text-secondary)",
          }}
        >
          {formatDateTime(v)}
        </span>
      ),
    },
  ];

  return (
    <div className="mt-4 space-y-6">
      {/* Latest result */}
      <div
        className="rounded-lg p-5 flex items-center gap-4"
        style={{
          backgroundColor: "var(--bg-surface-1)",
          border: "1px solid var(--border-default)",
        }}
      >
        <span
          style={{
            fontFamily: "'Onest', sans-serif",
            fontWeight: 500,
            fontSize: 14,
            color: "var(--text-secondary)",
          }}
        >
          Latest Result:
        </span>
        <StatusBadge status={compliance.latestResult} />
        {compliance.latestReason && (
          <span
            style={{
              fontFamily: "'Onest', sans-serif",
              fontSize: 13,
              color: "var(--text-secondary)",
            }}
          >
            &mdash; {compliance.latestReason}
          </span>
        )}
      </div>

      {/* Audit history */}
      {compliance.auditHistory.length > 0 && (
        <div
          className="rounded-lg overflow-hidden"
          style={{
            backgroundColor: "var(--bg-surface-1)",
            border: "1px solid var(--border-default)",
          }}
        >
          <div className="p-4 pb-0">
            <h3
              style={{
                fontFamily: "'Bricolage Grotesque', sans-serif",
                fontWeight: 600,
                fontSize: 20,
                lineHeight: 1.3,
                color: "var(--text-primary)",
                marginBottom: 12,
              }}
            >
              Audit History
            </h3>
          </div>
          <DataTable columns={auditColumns} data={compliance.auditHistory} />
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Main page component
// ---------------------------------------------------------------------------

export default function SkuDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { data: sku, isLoading } = useSku(id!);
  const {
    data: stateHistory,
    isLoading: historyLoading,
  } = useSkuStateHistory(id!);

  if (isLoading) {
    return (
      <div>
        <Skeleton className="h-8 w-24 rounded mb-4" />
        <Skeleton className="h-10 w-64 rounded mb-2" />
        <Skeleton className="h-6 w-32 rounded mb-6" />
        <div className="flex gap-2 mb-6">
          {Array.from({ length: 6 }).map((_, i) => (
            <Skeleton key={i} className="h-8 w-24 rounded" />
          ))}
        </div>
        <Skeleton className="h-64 rounded-lg" />
      </div>
    );
  }

  if (!sku) {
    return (
      <div>
        <Button
          variant="ghost"
          size="sm"
          onClick={() => navigate("/skus")}
          className="mb-4"
        >
          <ArrowLeft size={16} />
          <span
            style={{
              fontFamily: "'Onest', sans-serif",
              fontSize: 14,
              color: "var(--text-secondary)",
            }}
          >
            Back to SKUs
          </span>
        </Button>
        <p
          style={{
            fontFamily: "'Onest', sans-serif",
            fontSize: 14,
            color: "var(--text-secondary)",
          }}
        >
          SKU not found.
        </p>
      </div>
    );
  }

  return (
    <div>
      {/* Back button */}
      <Button
        variant="ghost"
        size="sm"
        onClick={() => navigate("/skus")}
        className="mb-4"
        style={{ color: "var(--text-secondary)" }}
      >
        <ArrowLeft size={16} />
        <span
          style={{
            fontFamily: "'Onest', sans-serif",
            fontSize: 14,
          }}
        >
          Back to SKUs
        </span>
      </Button>

      {/* Header: name + status badge */}
      <div className="flex items-center gap-3 mb-6">
        <h1
          style={{
            fontFamily: "'Bricolage Grotesque', sans-serif",
            fontWeight: 700,
            fontSize: 28,
            lineHeight: 1.2,
            color: "var(--text-primary)",
          }}
        >
          {sku.name}
        </h1>
        <StatusBadge status={sku.currentState} />
      </div>

      {/* Tabs */}
      <Tabs defaultValue="overview">
        <TabsList
          variant="line"
          style={{
            borderBottom: "1px solid var(--border-default)",
            paddingBottom: 0,
          }}
        >
          {[
            { value: "overview", label: "Overview" },
            { value: "cost", label: "Cost" },
            { value: "stress-test", label: "Stress Test" },
            { value: "pricing", label: "Pricing" },
            { value: "pnl", label: "P&L" },
            { value: "compliance", label: "Compliance" },
          ].map((tab) => (
            <TabsTrigger
              key={tab.value}
              value={tab.value}
              style={{
                fontFamily: "'Onest', sans-serif",
                fontWeight: 500,
                fontSize: 14,
              }}
            >
              {tab.label}
            </TabsTrigger>
          ))}
        </TabsList>

        <TabsContent value="overview">
          <OverviewTab
            sku={sku}
            history={stateHistory}
            historyLoading={historyLoading}
          />
        </TabsContent>

        <TabsContent value="cost">
          <CostTab />
        </TabsContent>

        <TabsContent value="stress-test">
          <StressTestTab />
        </TabsContent>

        <TabsContent value="pricing">
          <PricingTab skuId={id!} />
        </TabsContent>

        <TabsContent value="pnl">
          <PnlTab skuId={id!} />
        </TabsContent>

        <TabsContent value="compliance">
          <ComplianceTab skuId={id!} />
        </TabsContent>
      </Tabs>
    </div>
  );
}
