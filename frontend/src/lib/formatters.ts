/**
 * Format a numeric amount as currency.
 * e.g. formatMoney(12450) => "$12,450.00"
 *      formatMoney("12450.00", "EUR") => "€12,450.00"
 */
export function formatMoney(
  amount: string | number,
  currency: string = "USD",
): string {
  const num = typeof amount === "string" ? parseFloat(amount) : amount;
  if (isNaN(num)) return "--";
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(num);
}

/**
 * Format a numeric value as a percentage.
 * e.g. formatPercent(54.2) => "54.2%"
 *      formatPercent("31.85") => "31.9%"
 */
export function formatPercent(value: string | number): string {
  const num = typeof value === "string" ? parseFloat(value) : value;
  if (isNaN(num)) return "--";
  return `${parseFloat(num.toFixed(1))}%`;
}

/**
 * Format an ISO-8601 date string as a short date.
 * e.g. formatDate("2026-03-12T14:30:00Z") => "Mar 12, 2026"
 */
export function formatDate(isoString: string): string {
  const date = new Date(isoString);
  if (isNaN(date.getTime())) return "--";
  return new Intl.DateTimeFormat("en-US", {
    month: "short",
    day: "numeric",
    year: "numeric",
  }).format(date);
}

/**
 * Format an ISO-8601 date string as a date with time.
 * e.g. formatDateTime("2026-03-12T14:30:00Z") => "Mar 12, 2026 2:30 PM"
 */
export function formatDateTime(isoString: string): string {
  const date = new Date(isoString);
  if (isNaN(date.getTime())) return "--";
  return new Intl.DateTimeFormat("en-US", {
    month: "short",
    day: "numeric",
    year: "numeric",
    hour: "numeric",
    minute: "2-digit",
    hour12: true,
  }).format(date);
}

/**
 * Calculate days remaining in a validation window.
 * Returns 0 if the window has expired.
 *
 * e.g. daysRemaining("2026-03-01T00:00:00Z", 30) => 16  (if today is Mar 15)
 */
export function daysRemaining(
  createdAt: string,
  windowDays: number,
): number {
  const created = new Date(createdAt);
  if (isNaN(created.getTime())) return 0;
  const deadline = new Date(created.getTime() + windowDays * 24 * 60 * 60 * 1000);
  const now = new Date();
  const remaining = Math.ceil(
    (deadline.getTime() - now.getTime()) / (24 * 60 * 60 * 1000),
  );
  return Math.max(0, remaining);
}
