import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { apiGet, apiPost, apiPatch } from "@/api/client";
import type {
  VendorResponse,
  RegisterVendorRequest,
  UpdateChecklistRequest,
  ComputeScoreRequest,
  VendorScoreResponse,
} from "@/api/types";

// ---------------------------------------------------------------------------
// Queries
// ---------------------------------------------------------------------------

export function useVendors() {
  return useQuery({
    queryKey: ["vendors"],
    queryFn: () => apiGet<VendorResponse[]>("/api/vendors"),
  });
}

export function useVendor(id: string) {
  return useQuery({
    queryKey: ["vendors", id],
    queryFn: () => apiGet<VendorResponse>(`/api/vendors/${id}`),
    enabled: !!id,
  });
}

// ---------------------------------------------------------------------------
// Mutations
// ---------------------------------------------------------------------------

export function useRegisterVendor() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: RegisterVendorRequest) =>
      apiPost<VendorResponse>("/api/vendors", body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["vendors"] });
    },
  });
}

export function useUpdateChecklist() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, ...body }: { id: string } & UpdateChecklistRequest) =>
      apiPatch<VendorResponse>(`/api/vendors/${id}/checklist`, body),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: ["vendors", variables.id] });
    },
  });
}

export function useActivateVendor() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) =>
      apiPost<VendorResponse>(`/api/vendors/${id}/activate`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["vendors"] });
    },
  });
}

export function useComputeVendorScore() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, ...body }: { id: string } & ComputeScoreRequest) =>
      apiPost<VendorScoreResponse>(`/api/vendors/${id}/score`, body),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: ["vendors", variables.id] });
    },
  });
}
