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
  DemandScanStatusResponse,
  DemandCandidateResponse,
  CandidateRejectionResponse,
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

export function useDemandScanStatus() {
  return useQuery({
    queryKey: ["portfolio", "demand-scan", "status"],
    queryFn: () =>
      apiGet<DemandScanStatusResponse>("/api/portfolio/demand-scan/status"),
  });
}

export function useDemandCandidates() {
  return useQuery({
    queryKey: ["portfolio", "demand-scan", "candidates"],
    queryFn: () =>
      apiGet<DemandCandidateResponse[]>("/api/portfolio/demand-scan/candidates"),
  });
}

export function useDemandRejections() {
  return useQuery({
    queryKey: ["portfolio", "demand-scan", "rejections"],
    queryFn: () =>
      apiGet<CandidateRejectionResponse[]>("/api/portfolio/demand-scan/rejections"),
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

export function useTriggerDemandScan() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      apiPost<{ message: string }>("/api/portfolio/demand-scan/trigger"),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ["portfolio", "demand-scan"],
      });
    },
  });
}
