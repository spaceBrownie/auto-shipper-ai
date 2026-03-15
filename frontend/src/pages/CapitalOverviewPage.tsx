import { useState, useMemo } from "react";
import { useCapitalReserve, useSkuPnl } from "@/api/capital";
import {
  usePriorityRanking,
  useRefundAlerts,
} from "@/api/portfolio";
import { useSkus } from "@/api/skus";
import { ReserveGauge } from "@/components/ReserveGauge";
import { KpiCard } from "@/components/KpiCard";
import { DataTable, type Column } from "@/components/DataTable";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import {
  formatMoney,
  formatPercent,
} from "@/lib/formatters";
import type { PriorityRankingResponse } from "@/api/types";

function truncateUuid(uuid: string): string {
  return uuid.length > 8 ? uuid.slice(0, 8) + "..." : uuid;
}

export default function CapitalOverviewPage() {
  const { data: reserve, isLoading: reserveLoading } = useCapitalReserve();
  const { data: rankings, isLoading: rankingLoading } = usePriorityRanking();
  const { data: refundAlerts } = useRefundAlerts();
  const { data: allSkus } = useSkus();

  // SKU P&L section
  const [selectedSkuId, setSelectedSkuId] = useState("");
  const [pnlFrom, setPnlFrom] = useState(() => {
    const d = new Date();
    d.setDate(d.getDate() - 90);
    return d.toISOString().split("T")[0];
  });
  const [pnlTo, setPnlTo] = useState(() => {
    return new Date().toISOString().split("T")[0];
  });

  const { data: pnl, isLoading: pnlLoading } = useSkuPnl(
    selectedSkuId,
    pnlFrom,
    pnlTo,
  );

  const rankingColumns: Column<PriorityRankingResponse>[] = useMemo(
    () => [
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
            {truncateUuid(v)}
          </span>
        ),
      },
      {
        key: "avgNetMargin",
        header: "Avg Net Margin",
        render: (v: number) => formatPercent(v),
      },
      {
        key: "revenueVolume",
        header: "Revenue Volume",
        render: (v: number) => formatMoney(v),
      },
      {
        key: "riskFactor",
        header: "Risk Factor",
        render: (v: number) => v.toFixed(2),
      },
      {
        key: "riskAdjustedReturn",
        header: "Risk-Adj Return",
        render: (v: number) => formatMoney(v),
      },
    ],
    [],
  );

  // Sort rankings by risk-adjusted return descending
  const sortedRankings = useMemo(() => {
    if (!rankings) return [];
    return [...rankings].sort(
      (a, b) => b.riskAdjustedReturn - a.riskAdjustedReturn,
    );
  }, [rankings]);

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
        Capital Overview
      </h1>

      {/* Reserve Gauge */}
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
            Reserve Health
          </CardTitle>
        </CardHeader>
        <CardContent>
          {reserveLoading ? (
            <Skeleton
              className="h-14 w-full"
              style={{ backgroundColor: "var(--bg-surface-3)" }}
            />
          ) : reserve ? (
            <ReserveGauge
              balance={parseFloat(reserve.balanceAmount)}
              health={reserve.health as "HEALTHY" | "CRITICAL"}
            />
          ) : (
            <span
              style={{
                fontFamily: "'Onest', sans-serif",
                color: "var(--text-secondary)",
                fontSize: 14,
              }}
            >
              No reserve data available.
            </span>
          )}
        </CardContent>
      </Card>

      {/* Priority Ranking */}
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
            Priority Ranking (by risk-adjusted return)
          </CardTitle>
        </CardHeader>
        <CardContent>
          {rankingLoading ? (
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
          ) : sortedRankings.length === 0 ? (
            <span
              style={{
                fontFamily: "'Onest', sans-serif",
                color: "var(--text-secondary)",
                fontSize: 14,
              }}
            >
              No ranking data available.
            </span>
          ) : (
            <DataTable columns={rankingColumns} data={sortedRankings} />
          )}
        </CardContent>
      </Card>

      {/* Refund Alerts */}
      {refundAlerts && refundAlerts.elevatedSkuCount > 0 && (
        <Card
          className="mb-6"
          style={{
            backgroundColor: "var(--warning-dim)",
            border: "1px solid var(--warning)",
          }}
        >
          <CardContent className="pt-4">
            <div className="flex items-start gap-3">
              <span
                style={{
                  fontSize: 20,
                  lineHeight: 1,
                }}
              >
                &#9888;
              </span>
              <div>
                <p
                  style={{
                    fontFamily: "'Onest', sans-serif",
                    fontWeight: 600,
                    fontSize: 14,
                    color: "var(--warning)",
                    marginBottom: 4,
                  }}
                >
                  Refund Alert: {refundAlerts.elevatedSkuCount} SKU
                  {refundAlerts.elevatedSkuCount > 1 ? "s" : ""} with elevated
                  refund rate
                </p>
                <p
                  style={{
                    fontFamily: "'Onest', sans-serif",
                    fontSize: 13,
                    color: "var(--text-secondary)",
                    marginBottom: 4,
                  }}
                >
                  Portfolio avg refund rate:{" "}
                  <span
                    style={{
                      fontFamily: "'Martian Mono', monospace",
                      color: "var(--warning)",
                    }}
                  >
                    {formatPercent(refundAlerts.portfolioAvgRefundRate)}
                  </span>
                </p>
                <div className="flex flex-wrap gap-2 mt-2">
                  {refundAlerts.skuIds.map((id) => (
                    <span
                      key={id}
                      className="rounded px-2 py-0.5"
                      style={{
                        fontFamily: "'Martian Mono', monospace",
                        fontSize: 11,
                        backgroundColor: "var(--bg-surface-2)",
                        color: "var(--text-primary)",
                        border: "1px solid var(--border-default)",
                      }}
                    >
                      {truncateUuid(id)}
                    </span>
                  ))}
                </div>
              </div>
            </div>
          </CardContent>
        </Card>
      )}

      {/* SKU P&L */}
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
            SKU Profit &amp; Loss
          </CardTitle>
        </CardHeader>
        <CardContent>
          <div className="flex flex-wrap items-end gap-4 mb-6">
            <div className="w-64">
              <Label
                style={{
                  fontFamily: "'Onest', sans-serif",
                  fontSize: 12,
                  color: "var(--text-secondary)",
                }}
              >
                Select SKU
              </Label>
              <Select value={selectedSkuId} onValueChange={(val) => { if (val) setSelectedSkuId(val); }}>
                <SelectTrigger
                  style={{
                    backgroundColor: "var(--bg-surface-2)",
                    borderColor: "var(--border-default)",
                    color: "var(--text-primary)",
                  }}
                >
                  <SelectValue placeholder="Choose a SKU" />
                </SelectTrigger>
                <SelectContent
                  style={{
                    backgroundColor: "var(--bg-surface-2)",
                    borderColor: "var(--border-bright)",
                  }}
                >
                  {allSkus?.map((sku) => (
                    <SelectItem key={sku.id} value={sku.id}>
                      {sku.name} ({truncateUuid(sku.id)})
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div>
              <Label
                style={{
                  fontFamily: "'Onest', sans-serif",
                  fontSize: 12,
                  color: "var(--text-secondary)",
                }}
              >
                From
              </Label>
              <Input
                type="date"
                value={pnlFrom}
                onChange={(e) => setPnlFrom(e.target.value)}
                style={{
                  backgroundColor: "var(--bg-surface-2)",
                  borderColor: "var(--border-default)",
                  color: "var(--text-primary)",
                  fontFamily: "'Martian Mono', monospace",
                  fontSize: 13,
                }}
              />
            </div>
            <div>
              <Label
                style={{
                  fontFamily: "'Onest', sans-serif",
                  fontSize: 12,
                  color: "var(--text-secondary)",
                }}
              >
                To
              </Label>
              <Input
                type="date"
                value={pnlTo}
                onChange={(e) => setPnlTo(e.target.value)}
                style={{
                  backgroundColor: "var(--bg-surface-2)",
                  borderColor: "var(--border-default)",
                  color: "var(--text-primary)",
                  fontFamily: "'Martian Mono', monospace",
                  fontSize: 13,
                }}
              />
            </div>
          </div>

          {!selectedSkuId ? (
            <p
              style={{
                fontFamily: "'Onest', sans-serif",
                color: "var(--text-tertiary)",
                fontSize: 14,
              }}
            >
              Select a SKU to view its P&amp;L.
            </p>
          ) : pnlLoading ? (
            <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
              {Array.from({ length: 4 }).map((_, i) => (
                <Skeleton
                  key={i}
                  className="h-24"
                  style={{ backgroundColor: "var(--bg-surface-3)" }}
                />
              ))}
            </div>
          ) : pnl ? (
            <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
              <KpiCard
                label="Revenue"
                value={formatMoney(
                  pnl.totalRevenueAmount,
                  pnl.totalRevenueCurrency,
                )}
                accentColor="profit"
              />
              <KpiCard
                label="Cost"
                value={formatMoney(
                  pnl.totalCostAmount,
                  pnl.totalCostCurrency,
                )}
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
            <p
              style={{
                fontFamily: "'Onest', sans-serif",
                color: "var(--text-secondary)",
                fontSize: 14,
              }}
            >
              No P&amp;L data available for this SKU and date range.
            </p>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
