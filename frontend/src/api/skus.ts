import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { apiGet, apiPost } from "@/api/client";
import type {
  SkuResponse,
  SkuStateHistoryEntry,
  CreateSkuRequest,
  TransitionSkuRequest,
  VerifyCostsRequest,
  CostEnvelopeResponse,
  StressTestRequest,
  StressTestResponse,
} from "@/api/types";

// ---------------------------------------------------------------------------
// Queries
// ---------------------------------------------------------------------------

export function useSkus(state?: string) {
  const params = state ? `?state=${state}` : "";
  return useQuery({
    queryKey: ["skus", state],
    queryFn: () => apiGet<SkuResponse[]>(`/api/skus${params}`),
  });
}

export function useSku(id: string) {
  return useQuery({
    queryKey: ["skus", id],
    queryFn: () => apiGet<SkuResponse>(`/api/skus/${id}`),
    enabled: !!id,
  });
}

export function useSkuStateHistory(id: string) {
  return useQuery({
    queryKey: ["skus", id, "state-history"],
    queryFn: () =>
      apiGet<SkuStateHistoryEntry[]>(`/api/skus/${id}/state-history`),
    enabled: !!id,
  });
}

// ---------------------------------------------------------------------------
// Mutations
// ---------------------------------------------------------------------------

export function useCreateSku() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateSkuRequest) =>
      apiPost<SkuResponse>("/api/skus", body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["skus"] });
    },
  });
}

export function useTransitionSku() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, ...body }: { id: string } & TransitionSkuRequest) =>
      apiPost<SkuResponse>(`/api/skus/${id}/state`, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["skus"] });
    },
  });
}

export function useVerifyCosts() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, ...body }: { id: string } & VerifyCostsRequest) =>
      apiPost<CostEnvelopeResponse>(`/api/skus/${id}/verify-costs`, body),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: ["skus", variables.id] });
    },
  });
}

export function useRunStressTest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, ...body }: { id: string } & StressTestRequest) =>
      apiPost<StressTestResponse>(`/api/skus/${id}/stress-test`, body),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: ["skus", variables.id] });
    },
  });
}
