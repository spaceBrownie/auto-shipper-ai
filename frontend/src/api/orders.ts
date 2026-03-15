import { useQuery } from "@tanstack/react-query";
import { apiGet } from "@/api/client";
import type { OrderResponse, TrackingResponse } from "@/api/types";

export function useOrder(id: string) {
  return useQuery({
    queryKey: ["orders", id],
    queryFn: () => apiGet<OrderResponse>(`/api/orders/${id}`),
    enabled: !!id,
  });
}

export function useOrderTracking(id: string) {
  return useQuery({
    queryKey: ["orders", id, "tracking"],
    queryFn: () => apiGet<TrackingResponse>(`/api/orders/${id}/tracking`),
    enabled: !!id,
  });
}
