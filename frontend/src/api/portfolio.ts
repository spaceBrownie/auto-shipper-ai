import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { apiGet, apiPost } from "@/api/client";
import type {
  PortfolioSummaryResponse,
  ExperimentResponse,
  PriorityRankingResponse,
  KillRecommendationResponse,
  RefundAlertResponse,
  CreateExperimentRequest,
  ValidateExperimentRequest,
} from "@/api/types";

// ---------------------------------------------------------------------------
// Queries
// ---------------------------------------------------------------------------

export function usePortfolioSummary() {
  return useQuery({
    queryKey: ["portfolio", "summary"],
    queryFn: () =>
      apiGet<PortfolioSummaryResponse>("/api/portfolio/summary"),
  });
}

export function useExperiments() {
  return useQuery({
    queryKey: ["portfolio", "experiments"],
    queryFn: () =>
      apiGet<ExperimentResponse[]>("/api/portfolio/experiments"),
  });
}

export function usePriorityRanking() {
  return useQuery({
    queryKey: ["portfolio", "reallocation"],
    queryFn: () =>
      apiGet<PriorityRankingResponse[]>("/api/portfolio/reallocation"),
  });
}

export function useKillRecommendations() {
  return useQuery({
    queryKey: ["portfolio", "kill-recommendations"],
    queryFn: () =>
      apiGet<KillRecommendationResponse[]>(
        "/api/portfolio/kill-recommendations",
      ),
  });
}

export function useRefundAlerts() {
  return useQuery({
    queryKey: ["portfolio", "refund-alerts"],
    queryFn: () =>
      apiGet<RefundAlertResponse>("/api/portfolio/refund-alerts"),
  });
}

// ---------------------------------------------------------------------------
// Mutations
// ---------------------------------------------------------------------------

export function useCreateExperiment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateExperimentRequest) =>
      apiPost<ExperimentResponse>("/api/portfolio/experiments", body),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ["portfolio", "experiments"],
      });
    },
  });
}

export function useValidateExperiment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      id,
      ...body
    }: { id: string } & ValidateExperimentRequest) =>
      apiPost<ExperimentResponse>(
        `/api/portfolio/experiments/${id}/validate`,
        body,
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ["portfolio", "experiments"],
      });
    },
  });
}

export function useFailExperiment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) =>
      apiPost<ExperimentResponse>(
        `/api/portfolio/experiments/${id}/fail`,
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ["portfolio", "experiments"],
      });
    },
  });
}

export function useConfirmKill() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) =>
      apiPost<KillRecommendationResponse>(
        `/api/portfolio/kill-recommendations/${id}/confirm`,
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ["portfolio", "kill-recommendations"],
      });
    },
  });
}
