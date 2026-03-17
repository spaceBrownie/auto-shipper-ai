import {
  XAxis,
  YAxis,
  Tooltip,
  ReferenceLine,
  ResponsiveContainer,
  Area,
  ComposedChart,
} from "recharts";

interface MarginTrendChartProps {
  data: { date: string; grossMargin: number; netMargin: number }[];
  height?: number;
}

function formatShortDate(dateStr: string): string {
  const d = new Date(dateStr);
  return d.toLocaleDateString("en-US", { month: "short", day: "numeric" });
}

function CustomTooltip({ active, payload, label }: any) {
  if (!active || !payload?.length) return null;
  return (
    <div
      className="rounded-md px-3 py-2"
      style={{
        backgroundColor: "var(--bg-surface-2)",
        border: "1px solid var(--border-bright)",
        fontFamily: "'Martian Mono', monospace",
        fontSize: 12,
      }}
    >
      <div style={{ color: "var(--text-secondary)", marginBottom: 4 }}>
        {formatShortDate(label)}
      </div>
      {payload.map((entry: any) => (
        <div key={entry.dataKey} style={{ color: entry.color }}>
          {entry.dataKey === "grossMargin" ? "Gross" : "Net"}: {entry.value.toFixed(1)}%
        </div>
      ))}
    </div>
  );
}

export function MarginTrendChart({ data, height = 300 }: MarginTrendChartProps) {
  return (
    <ResponsiveContainer width="100%" height={height}>
      <ComposedChart data={data} margin={{ top: 10, right: 10, left: 0, bottom: 0 }}>
        <defs>
          <linearGradient id="grossFill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#34d399" stopOpacity={0.08} />
            <stop offset="100%" stopColor="#34d399" stopOpacity={0} />
          </linearGradient>
          <linearGradient id="netFill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#e5a00d" stopOpacity={0.08} />
            <stop offset="100%" stopColor="#e5a00d" stopOpacity={0} />
          </linearGradient>
        </defs>

        <XAxis
          dataKey="date"
          tickFormatter={formatShortDate}
          tick={{
            fontFamily: "'Martian Mono', monospace",
            fontSize: 11,
            fill: "var(--text-tertiary)",
          }}
          axisLine={false}
          tickLine={false}
        />
        <YAxis
          tickFormatter={(v: number) => `${v}%`}
          tick={{
            fontFamily: "'Martian Mono', monospace",
            fontSize: 11,
            fill: "var(--text-tertiary)",
          }}
          axisLine={false}
          tickLine={false}
          domain={[0, "auto"]}
        />

        <ReferenceLine
          y={50}
          stroke="#34d399"
          strokeOpacity={0.3}
          strokeDasharray="6 4"
          strokeWidth={1}
        />
        <ReferenceLine
          y={30}
          stroke="#f87171"
          strokeOpacity={0.3}
          strokeDasharray="6 4"
          strokeWidth={1}
        />

        <Tooltip content={<CustomTooltip />} />

        <Area
          type="monotone"
          dataKey="grossMargin"
          fill="url(#grossFill)"
          stroke="#34d399"
          strokeWidth={2}
          dot={false}
          activeDot={{ r: 3, fill: "#34d399" }}
          strokeLinecap="round"
        />
        <Area
          type="monotone"
          dataKey="netMargin"
          fill="url(#netFill)"
          stroke="#e5a00d"
          strokeWidth={2}
          dot={false}
          activeDot={{ r: 3, fill: "#e5a00d" }}
          strokeLinecap="round"
        />
      </ComposedChart>
    </ResponsiveContainer>
  );
}
