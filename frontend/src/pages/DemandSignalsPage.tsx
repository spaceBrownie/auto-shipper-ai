import { useState } from "react";
import {
  useDemandScanStatus,
  useDemandCandidates,
  useDemandRejections,
  useTriggerDemandScan,
} from "@/api/portfolio";
import { DataTable, type Column } from "@/components/DataTable";
import { StatusBadge } from "@/components/StatusBadge";
import { KpiCard } from "@/components/KpiCard";
import { SpriteScene } from "@/components/sprites/SpriteScene";
import { Scout } from "@/components/sprites/Scout";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { formatDateTime } from "@/lib/formatters";
import type {
  DemandCandidateResponse,
  CandidateRejectionResponse,
} from "@/api/types";

function ScoreBar({ value, max = 100 }: { value: number; max?: number }) {
  const pct = Math.min(100, Math.max(0, (value / max) * 100));
  const color =
    pct >= 70
      ? "var(--profit)"
      : pct >= 40
        ? "var(--warning)"
        : "var(--danger)";

  return (
    <div className="flex items-center gap-2">
      <div
        className="rounded-full overflow-hidden"
        style={{
          width: 48,
          height: 6,
          backgroundColor: "var(--bg-surface-3)",
        }}
      >
        <div
          className="h-full rounded-full"
          style={{
            width: `${pct}%`,
            backgroundColor: color,
            transition: "width 0.3s ease",
          }}
        />
      </div>
      <span
        style={{
          fontFamily: "'Martian Mono', monospace",
          fontSize: 12,
          color,
        }}
      >
        {value.toFixed(1)}
      </span>
    </div>
  );
}

export default function DemandSignalsPage() {
  const { data: status, isLoading: statusLoading } = useDemandScanStatus();
  const { data: candidates, isLoading: candidatesLoading } =
    useDemandCandidates();
  const { data: rejections, isLoading: rejectionsLoading } =
    useDemandRejections();
  const triggerScan = useTriggerDemandScan();

  const [showRejections, setShowRejections] = useState(false);

  const candidateColumns: Column<DemandCandidateResponse>[] = [
    { key: "productName", header: "Product Name" },
    {
      key: "category",
      header: "Category",
      render: (v: string) => (
        <span
          style={{
            fontFamily: "'Onest', sans-serif",
            fontSize: 13,
            color: "var(--text-secondary)",
          }}
        >
          {v}
        </span>
      ),
    },
    {
      key: "sourceType",
      header: "Source",
      render: (v: string) => (
        <span
          style={{
            fontFamily: "'Martian Mono', monospace",
            fontSize: 12,
          }}
        >
          {v}
        </span>
      ),
    },
    {
      key: "demandScore",
      header: "Demand",
      render: (v: number) => <ScoreBar value={v} />,
    },
    {
      key: "marginPotentialScore",
      header: "Margin",
      render: (v: number) => <ScoreBar value={v} />,
    },
    {
      key: "competitionScore",
      header: "Competition",
      render: (v: number) => <ScoreBar value={v} />,
    },
    {
      key: "compositeScore",
      header: "Composite",
      render: (v: number) => (
        <span
          style={{
            fontFamily: "'Martian Mono', monospace",
            fontWeight: 600,
            fontSize: 14,
            color:
              v >= 70
                ? "var(--profit)"
                : v >= 40
                  ? "var(--warning)"
                  : "var(--danger)",
          }}
        >
          {v.toFixed(1)}
        </span>
      ),
    },
    {
      key: "passed",
      header: "Status",
      render: (v: boolean) => (
        <StatusBadge status={v ? "CLEARED" : "FAILED"} size="sm" />
      ),
    },
  ];

  const rejectionColumns: Column<CandidateRejectionResponse>[] = [
    { key: "productName", header: "Product Name" },
    {
      key: "category",
      header: "Category",
      render: (v: string) => (
        <span
          style={{
            fontFamily: "'Onest', sans-serif",
            fontSize: 13,
            color: "var(--text-secondary)",
          }}
        >
          {v}
        </span>
      ),
    },
    {
      key: "sourceType",
      header: "Source",
      render: (v: string) => (
        <span
          style={{
            fontFamily: "'Martian Mono', monospace",
            fontSize: 12,
          }}
        >
          {v}
        </span>
      ),
    },
    {
      key: "rejectionReason",
      header: "Rejection Reason",
      render: (v: string) => (
        <span
          style={{
            fontFamily: "'Onest', sans-serif",
            fontSize: 13,
            color: "var(--danger)",
          }}
        >
          {v}
        </span>
      ),
    },
    {
      key: "compositeScore",
      header: "Composite",
      render: (v: number | null) =>
        v != null ? (
          <span
            style={{
              fontFamily: "'Martian Mono', monospace",
              fontSize: 12,
              color: "var(--text-secondary)",
            }}
          >
            {v.toFixed(1)}
          </span>
        ) : (
          <span style={{ color: "var(--text-tertiary)" }}>--</span>
        ),
    },
    {
      key: "createdAt",
      header: "Date",
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

  const noCandidates =
    !candidatesLoading && (!candidates || candidates.length === 0);
  const noRejections =
    !rejectionsLoading && (!rejections || rejections.length === 0);
  const isEmpty = noCandidates && noRejections && !statusLoading && !status?.lastRunId;

  return (
    <div>
      {/* Page title + Trigger button */}
      <div className="flex items-center justify-between mb-8">
        <h1
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

        <Button
          onClick={() => triggerScan.mutate()}
          disabled={triggerScan.isPending}
          style={{
            backgroundColor: "var(--accent)",
            color: "var(--bg-root)",
            fontFamily: "'Onest', sans-serif",
            fontWeight: 600,
          }}
        >
          {triggerScan.isPending ? "Scanning..." : "Trigger Scan"}
        </Button>
      </div>

      {/* Scan Status KPI Cards */}
      {statusLoading ? (
        <div className="grid grid-cols-2 gap-4 mb-6 md:grid-cols-4">
          {[...Array(4)].map((_, i) => (
            <Skeleton
              key={i}
              className="h-24"
              style={{ backgroundColor: "var(--bg-surface-3)" }}
            />
          ))}
        </div>
      ) : status ? (
        <>
          {/* Last run info bar */}
          <Card
            className="mb-4"
            style={{
              backgroundColor: "var(--bg-surface-1)",
              border: "1px solid var(--border-default)",
            }}
          >
            <CardContent className="pt-4 pb-4">
              <div className="flex flex-wrap items-center gap-6">
                <div>
                  <span
                    style={{
                      fontFamily: "'Onest', sans-serif",
                      fontSize: 12,
                      textTransform: "uppercase",
                      color: "var(--text-secondary)",
                      letterSpacing: "0.03em",
                      display: "block",
                      marginBottom: 4,
                    }}
                  >
                    Last Run
                  </span>
                  <span
                    style={{
                      fontFamily: "'Martian Mono', monospace",
                      fontSize: 13,
                      color: "var(--text-primary)",
                    }}
                  >
                    {status.lastRunCompletedAt
                      ? formatDateTime(status.lastRunCompletedAt)
                      : status.lastRunStartedAt
                        ? formatDateTime(status.lastRunStartedAt)
                        : "Never"}
                  </span>
                </div>
                {status.lastRunStatus && (
                  <div>
                    <span
                      style={{
                        fontFamily: "'Onest', sans-serif",
                        fontSize: 12,
                        textTransform: "uppercase",
                        color: "var(--text-secondary)",
                        letterSpacing: "0.03em",
                        display: "block",
                        marginBottom: 4,
                      }}
                    >
                      Status
                    </span>
                    <StatusBadge
                      status={status.lastRunStatus}
                      size="default"
                    />
                  </div>
                )}
              </div>
            </CardContent>
          </Card>

          {/* KPI Cards */}
          <div className="grid grid-cols-2 gap-4 mb-6 md:grid-cols-4">
            <KpiCard
              label="Sources Queried"
              value={status.sourcesQueried}
              accentColor="info"
            />
            <KpiCard
              label="Candidates Found"
              value={status.candidatesFound}
              accentColor="accent"
            />
            <KpiCard
              label="Experiments Created"
              value={status.experimentsCreated}
              accentColor="profit"
            />
            <KpiCard
              label="Rejections"
              value={status.rejections}
              accentColor="danger"
            />
          </div>
        </>
      ) : null}

      {/* Empty state */}
      {isEmpty && (
        <div className="flex flex-col items-center justify-center py-24">
          <SpriteScene
            message="No demand scan data yet. Hit Trigger Scan to discover opportunities!"
            animation="idle"
          >
            <Scout size={48} />
          </SpriteScene>
        </div>
      )}

      {/* Scored Candidates Table */}
      {!isEmpty && (
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
              Scored Candidates
            </CardTitle>
          </CardHeader>
          <CardContent>
            {candidatesLoading ? (
              <div className="flex flex-col gap-2">
                <Skeleton
                  className="h-10 w-full"
                  style={{ backgroundColor: "var(--bg-surface-3)" }}
                />
                <Skeleton
                  className="h-10 w-full"
                  style={{ backgroundColor: "var(--bg-surface-3)" }}
                />
                <Skeleton
                  className="h-10 w-full"
                  style={{ backgroundColor: "var(--bg-surface-3)" }}
                />
              </div>
            ) : candidates && candidates.length > 0 ? (
              <DataTable columns={candidateColumns} data={candidates} />
            ) : (
              <span
                style={{
                  fontFamily: "'Onest', sans-serif",
                  color: "var(--text-secondary)",
                  fontSize: 14,
                }}
              >
                No candidates from the latest scan.
              </span>
            )}
          </CardContent>
        </Card>
      )}

      {/* Rejections (collapsible) */}
      {!isEmpty && (
        <Card
          style={{
            backgroundColor: "var(--bg-surface-1)",
            border: "1px solid var(--border-default)",
          }}
        >
          <CardHeader>
            <div className="flex items-center justify-between">
              <CardTitle
                style={{
                  fontFamily: "'Bricolage Grotesque', sans-serif",
                  fontWeight: 600,
                  fontSize: 20,
                  color: "var(--text-primary)",
                }}
              >
                Rejections
                {rejections && rejections.length > 0 && (
                  <span
                    style={{
                      fontFamily: "'Martian Mono', monospace",
                      fontSize: 14,
                      fontWeight: 400,
                      color: "var(--text-tertiary)",
                      marginLeft: 8,
                    }}
                  >
                    ({rejections.length})
                  </span>
                )}
              </CardTitle>
              <Button
                size="sm"
                variant="outline"
                onClick={() => setShowRejections(!showRejections)}
                style={{
                  borderColor: "var(--border-default)",
                  color: "var(--text-secondary)",
                  fontFamily: "'Onest', sans-serif",
                  fontSize: 12,
                }}
              >
                {showRejections ? "Collapse" : "Expand"}
              </Button>
            </div>
          </CardHeader>
          {showRejections && (
            <CardContent>
              {rejectionsLoading ? (
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
              ) : rejections && rejections.length > 0 ? (
                <DataTable columns={rejectionColumns} data={rejections} />
              ) : (
                <span
                  style={{
                    fontFamily: "'Onest', sans-serif",
                    color: "var(--text-secondary)",
                    fontSize: 14,
                  }}
                >
                  No rejections recorded.
                </span>
              )}
            </CardContent>
          )}
        </Card>
      )}
    </div>
  );
}
