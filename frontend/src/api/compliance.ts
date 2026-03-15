import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { apiGet, apiPost } from "@/api/client";
import type {
  ComplianceStatusResponse,
  ManualCheckRequest,
} from "@/api/types";

export function useComplianceStatus(skuId: string) {
  return useQuery({
    queryKey: ["compliance", "skus", skuId],
    queryFn: () =>
      apiGet<ComplianceStatusResponse>(`/api/compliance/skus/${skuId}`),
    enabled: !!skuId,
  });
}

export function useRunComplianceCheck() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      id,
      ...body
    }: { id: string } & Partial<ManualCheckRequest>) =>
      apiPost<ComplianceStatusResponse>(
        `/api/compliance/skus/${id}/check`,
        Object.keys(body).length > 0 ? body : undefined,
      ),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({
        queryKey: ["compliance", "skus", variables.id],
      });
    },
  });
}
