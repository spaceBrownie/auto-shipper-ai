import type { ReactNode } from "react";
import { Sidebar } from "./Sidebar";
import { EnginePulse } from "./EnginePulse";

interface AppShellProps {
  children: ReactNode;
}

export function AppShell({ children }: AppShellProps) {
  return (
    <div className="flex min-h-screen" style={{ backgroundColor: "var(--bg-root)" }}>
      <Sidebar />
      <main className="flex-1 ml-64">
        <EnginePulse />
        <div className="p-8 overflow-y-auto h-screen">{children}</div>
      </main>
    </div>
  );
}
