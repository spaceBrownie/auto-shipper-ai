import { useQuery } from "@tanstack/react-query";
import { apiGet } from "@/api/client";
import type {
  ReserveResponse,
  SkuPnlResponse,
  MarginSnapshotResponse,
} from "@/api/types";

export function useCapitalReserve() {
  return useQuery({
    queryKey: ["capital", "reserve"],
    queryFn: () => apiGet<ReserveResponse>("/api/capital/reserve"),
  });
}

export function useSkuPnl(id: string, from: string, to: string) {
  return useQuery({
    queryKey: ["capital", "skus", id, "pnl", from, to],
    queryFn: () =>
      apiGet<SkuPnlResponse>(
        `/api/capital/skus/${id}/pnl?from=${from}&to=${to}`,
      ),
    enabled: !!id && !!from && !!to,
  });
}

export function useMarginHistory(id: string, from: string, to: string) {
  return useQuery({
    queryKey: ["capital", "skus", id, "margin-history", from, to],
    queryFn: () =>
      apiGet<MarginSnapshotResponse[]>(
        `/api/capital/skus/${id}/margin-history?from=${from}&to=${to}`,
      ),
    enabled: !!id && !!from && !!to,
  });
}
