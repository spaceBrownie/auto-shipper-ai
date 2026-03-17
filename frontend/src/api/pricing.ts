import { useQuery } from "@tanstack/react-query";
import type { PricingResponse } from "@/api/types";

const BASE_URL = import.meta.env.VITE_API_BASE_URL || "";

export function useSkuPricing(id: string) {
  return useQuery({
    queryKey: ["skus", id, "pricing"],
    queryFn: async (): Promise<PricingResponse | null> => {
      const response = await fetch(`${BASE_URL}/api/skus/${id}/pricing`, {
        method: "GET",
        headers: { Accept: "application/json" },
      });
      if (response.status === 404) return null;
      if (!response.ok) throw new Error(`Request failed (${response.status})`);
      return response.json();
    },
    enabled: !!id,
  });
}
