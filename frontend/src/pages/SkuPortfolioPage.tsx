import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Pause, X } from "lucide-react";
import { useSkus, useTransitionSku } from "@/api/skus";
import type { SkuResponse } from "@/api/types";
import { KpiCard } from "@/components/KpiCard";
import { DataTable, type Column } from "@/components/DataTable";
import { StatusBadge } from "@/components/StatusBadge";
import { SpriteScene } from "@/components/sprites/SpriteScene";
import { Shipper } from "@/components/sprites/Shipper";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectTrigger,
  SelectValue,
  SelectContent,
  SelectItem,
} from "@/components/ui/select";
import { formatDate } from "@/lib/formatters";

const SKU_STATES = [
  "All",
  "Ideation",
  "ValidationPending",
  "CostGating",
  "StressTesting",
  "Listed",
  "Paused",
  "Scaled",
  "Terminated",
] as const;

function countByState(skus: SkuResponse[], ...states: string[]): number {
  return skus.filter((s) => states.includes(s.currentState)).length;
}

export default function SkuPortfolioPage() {
  const [stateFilter, setStateFilter] = useState<string>("All");
  const navigate = useNavigate();
  const transitionSku = useTransitionSku();

  // Always fetch all SKUs so we can compute KPI counts regardless of filter
  const { data: allSkus, isLoading: allLoading } = useSkus();
  // Fetch filtered set when a filter is active
  const queryState = stateFilter === "All" ? undefined : stateFilter;
  const { data: filteredSkus, isLoading: filteredLoading } = useSkus(queryState);

  const skus = stateFilter === "All" ? allSkus : filteredSkus;
  const isLoading = stateFilter === "All" ? allLoading : filteredLoading;

  const activeCount = allSkus
    ? countByState(allSkus, "Listed", "Scaled", "Ideation", "ValidationPending", "CostGating", "StressTesting")
    : 0;
  const listedCount = allSkus ? countByState(allSkus, "Listed") : 0;
  const pausedCount = allSkus ? countByState(allSkus, "Paused") : 0;
  const terminatedCount = allSkus ? countByState(allSkus, "Terminated") : 0;

  function handlePause(e: React.MouseEvent, sku: SkuResponse) {
    e.stopPropagation();
    transitionSku.mutate({
      id: sku.id,
      targetState: "Paused",
      reason: "Manual pause from dashboard",
    });
  }

  function handleTerminate(e: React.MouseEvent, sku: SkuResponse) {
    e.stopPropagation();
    transitionSku.mutate({
      id: sku.id,
      targetState: "Terminated",
      reason: "Manual termination from dashboard",
    });
  }

  const columns: Column<SkuResponse>[] = [
    {
      key: "name",
      header: "Name",
    },
    {
      key: "category",
      header: "Category",
    },
    {
      key: "currentState",
      header: "State",
      render: (value: string) => <StatusBadge status={value} />,
    },
    {
      key: "createdAt",
      header: "Created",
      render: (value: string) => (
        <span
          style={{
            fontFamily: "'Martian Mono', monospace",
            fontSize: 12,
            color: "var(--text-secondary)",
          }}
        >
          {formatDate(value)}
        </span>
      ),
    },
    {
      key: "actions",
      header: "Actions",
      render: (_: unknown, row: SkuResponse) => {
        if (row.currentState === "Terminated") return null;
        return (
          <div className="flex items-center gap-1">
            {row.currentState !== "Paused" && (
              <Button
                variant="ghost"
                size="icon-xs"
                onClick={(e) => handlePause(e, row)}
                disabled={transitionSku.isPending}
                title="Pause SKU"
              >
                <Pause size={14} style={{ color: "var(--warning)" }} />
              </Button>
            )}
            <Button
              variant="ghost"
              size="icon-xs"
              onClick={(e) => handleTerminate(e, row)}
              disabled={transitionSku.isPending}
              title="Terminate SKU"
            >
              <X size={14} style={{ color: "var(--danger)" }} />
            </Button>
          </div>
        );
      },
    },
  ];

  // Loading state
  if (isLoading || allLoading) {
    return (
      <div>
        <h1
          style={{
            fontFamily: "'Bricolage Grotesque', sans-serif",
            fontWeight: 700,
            fontSize: 28,
            lineHeight: 1.2,
            color: "var(--text-primary)",
            marginBottom: 24,
          }}
        >
          SKU Portfolio
        </h1>
        <div className="grid grid-cols-4 gap-4 mb-6">
          {Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} className="h-24 rounded-lg" />
          ))}
        </div>
        <Skeleton className="h-8 w-48 mb-4 rounded-lg" />
        <div className="space-y-2">
          {Array.from({ length: 5 }).map((_, i) => (
            <Skeleton key={i} className="h-12 rounded-lg" />
          ))}
        </div>
      </div>
    );
  }

  // Empty state
  if (!skus || skus.length === 0) {
    return (
      <div>
        <h1
          style={{
            fontFamily: "'Bricolage Grotesque', sans-serif",
            fontWeight: 700,
            fontSize: 28,
            lineHeight: 1.2,
            color: "var(--text-primary)",
            marginBottom: 24,
          }}
        >
          SKU Portfolio
        </h1>
        <div className="grid grid-cols-4 gap-4 mb-6">
          <KpiCard label="Active SKUs" value={0} accentColor="info" />
          <KpiCard label="Listed" value={0} accentColor="profit" />
          <KpiCard label="Paused" value={0} accentColor="warning" />
          <KpiCard label="Terminated" value={0} accentColor="danger" />
        </div>
        <div className="flex flex-col items-center justify-center py-20">
          <SpriteScene
            message="No SKUs yet. Let's ship something!"
            animation="idle"
          >
            <Shipper size={48} />
          </SpriteScene>
        </div>
      </div>
    );
  }

  return (
    <div>
      {/* Page header */}
      <div className="flex items-center justify-between mb-6">
        <h1
          style={{
            fontFamily: "'Bricolage Grotesque', sans-serif",
            fontWeight: 700,
            fontSize: 28,
            lineHeight: 1.2,
            color: "var(--text-primary)",
          }}
        >
          SKU Portfolio
        </h1>
        <Select value={stateFilter} onValueChange={(val) => { if (val) setStateFilter(val); }}>
          <SelectTrigger
            style={{
              backgroundColor: "var(--bg-surface-2)",
              borderColor: "var(--border-bright)",
              color: "var(--text-primary)",
              fontFamily: "'Onest', sans-serif",
              fontSize: 14,
              minWidth: 180,
            }}
          >
            <SelectValue placeholder="Filter by state" />
          </SelectTrigger>
          <SelectContent
            style={{
              backgroundColor: "var(--bg-surface-2)",
              borderColor: "var(--border-bright)",
            }}
          >
            {SKU_STATES.map((state) => (
              <SelectItem key={state} value={state}>
                {state === "All" ? "All States" : state}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* KPI Cards */}
      <div className="grid grid-cols-4 gap-4 mb-6">
        <KpiCard label="Active SKUs" value={activeCount} accentColor="info" />
        <KpiCard label="Listed" value={listedCount} accentColor="profit" />
        <KpiCard label="Paused" value={pausedCount} accentColor="warning" />
        <KpiCard label="Terminated" value={terminatedCount} accentColor="danger" />
      </div>

      {/* Data Table */}
      <div
        className="rounded-lg overflow-hidden"
        style={{
          backgroundColor: "var(--bg-surface-1)",
          border: "1px solid var(--border-default)",
        }}
      >
        <DataTable
          columns={columns}
          data={skus}
          onRowClick={(row) => navigate(`/skus/${row.id}`)}
        />
      </div>
    </div>
  );
}
