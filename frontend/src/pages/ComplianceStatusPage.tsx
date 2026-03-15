import { useState } from "react";
import { useSkus } from "@/api/skus";
import {
  useComplianceStatus,
  useRunComplianceCheck,
} from "@/api/compliance";
import { StatusBadge } from "@/components/StatusBadge";
import { DataTable, type Column } from "@/components/DataTable";
import { SpriteScene } from "@/components/sprites/SpriteScene";
import { Guard } from "@/components/sprites/Guard";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { formatDateTime } from "@/lib/formatters";
import type { AuditEntry } from "@/api/types";

export default function ComplianceStatusPage() {
  const { data: allSkus } = useSkus();
  const [selectedSkuId, setSelectedSkuId] = useState("");

  const { data: compliance, isLoading: complianceLoading } =
    useComplianceStatus(selectedSkuId);
  const runCheck = useRunComplianceCheck();

  function handleRunCheck() {
    if (!selectedSkuId) return;
    runCheck.mutate({ id: selectedSkuId, skuId: selectedSkuId });
  }

  const auditColumns: Column<AuditEntry>[] = [
    {
      key: "checkType",
      header: "Check Type",
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
      key: "result",
      header: "Result",
      render: (v: string) => <StatusBadge status={v} />,
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
          {v || "--"}
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
            fontSize: 13,
            color: v ? "var(--text-primary)" : "var(--text-tertiary)",
          }}
        >
          {v || "--"}
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
        Compliance Status
      </h1>

      {/* SKU Selector + Run Check */}
      <div className="flex flex-wrap items-end gap-4 mb-6">
        <div className="w-72">
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
                  {sku.name} (
                  {sku.id.length > 8 ? sku.id.slice(0, 8) + "..." : sku.id})
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <Button
          onClick={handleRunCheck}
          disabled={!selectedSkuId || runCheck.isPending}
          style={{
            backgroundColor: "var(--accent)",
            color: "var(--bg-root)",
            fontFamily: "'Onest', sans-serif",
            fontWeight: 600,
          }}
        >
          {runCheck.isPending ? "Running..." : "Run Compliance Check"}
        </Button>
      </div>

      {/* No selection state */}
      {!selectedSkuId && (
        <div className="flex flex-col items-center justify-center py-24">
          <SpriteScene
            message="Select a SKU to view compliance status"
            animation="idle"
          >
            <Guard size={48} />
          </SpriteScene>
        </div>
      )}

      {/* Loading */}
      {selectedSkuId && complianceLoading && (
        <div className="flex flex-col gap-4">
          <Skeleton
            className="h-24 w-full"
            style={{ backgroundColor: "var(--bg-surface-3)" }}
          />
          <Skeleton
            className="h-48 w-full"
            style={{ backgroundColor: "var(--bg-surface-3)" }}
          />
        </div>
      )}

      {/* Compliance data */}
      {selectedSkuId && !complianceLoading && compliance && (
        <>
          {/* Latest result */}
          <Card
            className="mb-6"
            style={{
              backgroundColor: "var(--bg-surface-1)",
              border: "1px solid var(--border-default)",
            }}
          >
            <CardContent className="pt-6">
              <div className="flex items-center gap-4">
                <div>
                  <span
                    style={{
                      fontFamily: "'Onest', sans-serif",
                      fontSize: 12,
                      textTransform: "uppercase",
                      color: "var(--text-secondary)",
                      letterSpacing: "0.03em",
                      display: "block",
                      marginBottom: 8,
                    }}
                  >
                    Latest Result
                  </span>
                  <StatusBadge status={compliance.latestResult} size="default" />
                </div>
                {compliance.latestReason && (
                  <div className="ml-6">
                    <span
                      style={{
                        fontFamily: "'Onest', sans-serif",
                        fontSize: 12,
                        textTransform: "uppercase",
                        color: "var(--text-secondary)",
                        letterSpacing: "0.03em",
                        display: "block",
                        marginBottom: 8,
                      }}
                    >
                      Reason
                    </span>
                    <span
                      style={{
                        fontFamily: "'Onest', sans-serif",
                        fontSize: 14,
                        color: "var(--text-primary)",
                      }}
                    >
                      {compliance.latestReason}
                    </span>
                  </div>
                )}
              </div>
            </CardContent>
          </Card>

          {/* Audit History */}
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
                Audit History
              </CardTitle>
            </CardHeader>
            <CardContent>
              {compliance.auditHistory && compliance.auditHistory.length > 0 ? (
                <DataTable
                  columns={auditColumns}
                  data={compliance.auditHistory}
                />
              ) : (
                <span
                  style={{
                    fontFamily: "'Onest', sans-serif",
                    color: "var(--text-secondary)",
                    fontSize: 14,
                  }}
                >
                  No audit history available.
                </span>
              )}
            </CardContent>
          </Card>
        </>
      )}

      {/* No data for selected SKU */}
      {selectedSkuId && !complianceLoading && !compliance && (
        <Card
          style={{
            backgroundColor: "var(--bg-surface-1)",
            border: "1px solid var(--border-default)",
          }}
        >
          <CardContent className="pt-6">
            <p
              style={{
                fontFamily: "'Onest', sans-serif",
                color: "var(--text-secondary)",
                fontSize: 14,
              }}
            >
              No compliance data available for this SKU. Run a compliance check
              to get started.
            </p>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
