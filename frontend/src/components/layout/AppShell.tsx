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
      <main className="flex-1 ml-64 overflow-y-auto">
        <EnginePulse />
        <div className="p-8">{children}</div>
      </main>
    </div>
  );
}
