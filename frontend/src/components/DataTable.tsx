import type { ReactNode } from "react";
import {
  Table,
  TableHeader,
  TableBody,
  TableHead,
  TableRow,
  TableCell,
} from "@/components/ui/table";

export interface Column<T> {
  key: string;
  header: string;
  render?: (value: any, row: T) => ReactNode;
  className?: string;
}

interface DataTableProps<T> {
  columns: Column<T>[];
  data: T[];
  onRowClick?: (row: T) => void;
}

export function DataTable<T extends Record<string, any>>({
  columns,
  data,
  onRowClick,
}: DataTableProps<T>) {
  return (
    <Table>
      <TableHeader>
        <TableRow style={{ borderColor: "var(--border-default)" }}>
          {columns.map((col) => (
            <TableHead
              key={col.key}
              className={col.className}
              style={{
                fontFamily: "'Onest', sans-serif",
                fontWeight: 600,
                fontSize: 12,
                textTransform: "uppercase",
                color: "var(--text-tertiary)",
                letterSpacing: "0.03em",
              }}
            >
              {col.header}
            </TableHead>
          ))}
        </TableRow>
      </TableHeader>
      <TableBody>
        {data.map((row, i) => (
          <TableRow
            key={i}
            onClick={() => onRowClick?.(row)}
            className="transition-colors duration-150"
            style={{
              cursor: onRowClick ? "pointer" : "default",
              borderColor: "var(--border-default)",
            }}
            onMouseEnter={(e) => {
              e.currentTarget.style.backgroundColor = "var(--bg-surface-3)";
            }}
            onMouseLeave={(e) => {
              e.currentTarget.style.backgroundColor = "transparent";
            }}
          >
            {columns.map((col) => {
              const value = row[col.key];
              const isNumeric =
                typeof value === "number" ||
                (typeof value === "string" && /^[\d.$,%+-]+$/.test(value));

              return (
                <TableCell
                  key={col.key}
                  className={col.className}
                  style={{
                    fontFamily: isNumeric
                      ? "'Martian Mono', monospace"
                      : "'Onest', sans-serif",
                    fontWeight: 400,
                    fontSize: 14,
                    color: "var(--text-primary)",
                  }}
                >
                  {col.render ? col.render(value, row) : String(value ?? "")}
                </TableCell>
              );
            })}
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
