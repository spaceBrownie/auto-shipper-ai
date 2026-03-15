import type { CostEnvelopeResponse } from "@/api/types";

interface CostBreakdownTableProps {
  envelope: CostEnvelopeResponse;
}

interface CostRow {
  label: string;
  amount: number;
}

export function CostBreakdownTable({ envelope }: CostBreakdownTableProps) {
  const rows: CostRow[] = [
    { label: "Supplier Unit Cost", amount: envelope.supplierUnitCost },
    { label: "Inbound Shipping", amount: envelope.inboundShipping },
    { label: "Outbound Shipping", amount: envelope.outboundShipping },
    { label: "Platform Fee", amount: envelope.platformFee },
    { label: "Processing Fee", amount: envelope.processingFee },
    { label: "Packaging", amount: envelope.packagingCost },
    { label: "Return Handling", amount: envelope.returnHandlingCost },
    { label: "CAC", amount: envelope.customerAcquisitionCost },
    { label: "Warehousing", amount: envelope.warehousingCost },
    { label: "Customer Service", amount: envelope.customerServiceCost },
    { label: "Refund Allowance", amount: envelope.refundAllowance },
    { label: "Chargeback Allowance", amount: envelope.chargebackAllowance },
    { label: "Taxes & Duties", amount: envelope.taxesAndDuties },
  ];

  const total = envelope.fullyBurdened;
  const maxAmount = Math.max(...rows.map((r) => r.amount), 0.01);

  const formatCurrency = (amount: number) =>
    new Intl.NumberFormat("en-US", {
      style: "currency",
      currency: envelope.currency || "USD",
      minimumFractionDigits: 2,
    }).format(amount);

  const formatPercent = (amount: number) =>
    total > 0 ? ((amount / total) * 100).toFixed(1) + "%" : "0.0%";

  return (
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
            <th
              className="text-left px-4 py-2"
              style={{
                fontFamily: "'Onest', sans-serif",
                fontWeight: 600,
                fontSize: 12,
                textTransform: "uppercase",
                color: "var(--text-tertiary)",
              }}
            >
              Cost Component
            </th>
            <th
              className="text-right px-4 py-2"
              style={{
                fontFamily: "'Onest', sans-serif",
                fontWeight: 600,
                fontSize: 12,
                textTransform: "uppercase",
                color: "var(--text-tertiary)",
              }}
            >
              Amount
            </th>
            <th
              className="text-right px-4 py-2"
              style={{
                fontFamily: "'Onest', sans-serif",
                fontWeight: 600,
                fontSize: 12,
                textTransform: "uppercase",
                color: "var(--text-tertiary)",
              }}
            >
              Share
            </th>
            <th className="w-24 px-4 py-2" />
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr
              key={row.label}
              style={{ borderBottom: "1px solid var(--border-default)" }}
            >
              <td
                className="px-4 py-2"
                style={{
                  fontFamily: "'Onest', sans-serif",
                  fontWeight: 400,
                  fontSize: 14,
                  color: "var(--text-primary)",
                }}
              >
                {row.label}
              </td>
              <td
                className="text-right px-4 py-2"
                style={{
                  fontFamily: "'Martian Mono', monospace",
                  fontWeight: 400,
                  fontSize: 14,
                  color: "var(--text-primary)",
                }}
              >
                {formatCurrency(row.amount)}
              </td>
              <td
                className="text-right px-4 py-2"
                style={{
                  fontFamily: "'Martian Mono', monospace",
                  fontWeight: 400,
                  fontSize: 12,
                  color: "var(--text-secondary)",
                }}
              >
                {formatPercent(row.amount)}
              </td>
              <td className="px-4 py-2">
                <div
                  className="rounded"
                  style={{
                    height: 8,
                    backgroundColor: "var(--bg-surface-3)",
                    width: "100%",
                  }}
                >
                  <div
                    className="h-full rounded"
                    style={{
                      width: `${(row.amount / maxAmount) * 100}%`,
                      backgroundColor: "rgba(229, 160, 13, 0.4)",
                      transition: "width 400ms ease-out",
                    }}
                  />
                </div>
              </td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr
            style={{
              borderTop: "2px solid var(--border-bright)",
            }}
          >
            <td
              className="px-4 py-3"
              style={{
                fontFamily: "'Bricolage Grotesque', sans-serif",
                fontWeight: 600,
                fontSize: 14,
                color: "var(--accent)",
              }}
            >
              FULLY BURDENED
            </td>
            <td
              className="text-right px-4 py-3"
              style={{
                fontFamily: "'Martian Mono', monospace",
                fontWeight: 500,
                fontSize: 14,
                color: "var(--accent)",
              }}
            >
              {formatCurrency(total)}
            </td>
            <td
              className="text-right px-4 py-3"
              style={{
                fontFamily: "'Martian Mono', monospace",
                fontWeight: 500,
                fontSize: 12,
                color: "var(--accent)",
              }}
            >
              100.0%
            </td>
            <td className="px-4 py-3" />
          </tr>
        </tfoot>
      </table>
    </div>
  );
}
