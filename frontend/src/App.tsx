import { useState, useEffect, useCallback } from "react";
import {
  createBrowserRouter,
  Navigate,
  Outlet,
  RouterProvider,
} from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Toaster } from "@/components/ui/sonner";
import { toast } from "sonner";
import { AppShell } from "@/components/layout/AppShell";
import { useKonamiCode } from "@/lib/konami";

import SkuPortfolioPage from "@/pages/SkuPortfolioPage";
import SkuDetailPage from "@/pages/SkuDetailPage";
import CostGateRunnerPage from "@/pages/CostGateRunnerPage";
import MarginMonitorPage from "@/pages/MarginMonitorPage";
import ExperimentTrackerPage from "@/pages/ExperimentTrackerPage";
import DemandSignalsPage from "@/pages/DemandSignalsPage";
import VendorScorecardPage from "@/pages/VendorScorecardPage";
import CapitalOverviewPage from "@/pages/CapitalOverviewPage";
import ComplianceStatusPage from "@/pages/ComplianceStatusPage";
import KillLogPage from "@/pages/KillLogPage";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: 1,
    },
  },
});

function AppLayout() {
  const [rainbowActive, setRainbowActive] = useState(false);

  const handleKonami = useCallback(() => {
    toast("🎉 You found the crew! All systems nominal.");
    setRainbowActive(true);
  }, []);

  useKonamiCode(handleKonami);

  useEffect(() => {
    if (!rainbowActive) return;

    const pulseBar = document.querySelector(".engine-pulse");
    if (pulseBar) {
      pulseBar.classList.add("engine-pulse--rainbow");
    }

    const timeout = setTimeout(() => {
      setRainbowActive(false);
      if (pulseBar) {
        pulseBar.classList.remove("engine-pulse--rainbow");
      }
    }, 10_000);

    return () => {
      clearTimeout(timeout);
      if (pulseBar) {
        pulseBar.classList.remove("engine-pulse--rainbow");
      }
    };
  }, [rainbowActive]);

  return (
    <AppShell>
      <Outlet />
    </AppShell>
  );
}

const router = createBrowserRouter([
  {
    element: <AppLayout />,
    children: [
      { path: "/", element: <Navigate to="/skus" replace /> },
      { path: "/skus", element: <SkuPortfolioPage /> },
      { path: "/skus/:id", element: <SkuDetailPage /> },
      { path: "/cost-gate", element: <CostGateRunnerPage /> },
      { path: "/margin", element: <MarginMonitorPage /> },
      { path: "/experiments", element: <ExperimentTrackerPage /> },
      { path: "/demand", element: <DemandSignalsPage /> },
      { path: "/vendors", element: <VendorScorecardPage /> },
      { path: "/capital", element: <CapitalOverviewPage /> },
      { path: "/compliance", element: <ComplianceStatusPage /> },
      { path: "/kill-log", element: <KillLogPage /> },
    ],
  },
]);

function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
      <Toaster />
    </QueryClientProvider>
  );
}

export default App;
