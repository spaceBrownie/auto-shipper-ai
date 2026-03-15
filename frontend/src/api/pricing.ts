import { useQuery } from "@tanstack/react-query";
import { apiGet } from "@/api/client";
import type { PricingResponse } from "@/api/types";

export function useSkuPricing(id: string) {
  return useQuery({
    queryKey: ["skus", id, "pricing"],
    queryFn: () => apiGet<PricingResponse>(`/api/skus/${id}/pricing`),
    enabled: !!id,
  });
}
