import { NavLink } from "react-router-dom";
import {
  Package,
  ShieldCheck,
  TrendingUp,
  FlaskConical,
  Radio,
  Truck,
  Vault,
  Scale,
  Skull,
} from "lucide-react";
import type { ReactNode } from "react";

interface NavItem {
  to: string;
  label: string;
  icon: ReactNode;
}

const sections: { title: string; items: NavItem[] }[] = [
  {
    title: "OPERATE",
    items: [
      { to: "/", label: "SKU Portfolio", icon: <Package size={18} /> },
      {
        to: "/cost-gate",
        label: "Cost Gate Runner",
        icon: <ShieldCheck size={18} />,
      },
    ],
  },
  {
    title: "INTELLIGENCE",
    items: [
      {
        to: "/margin",
        label: "Margin Monitor",
        icon: <TrendingUp size={18} />,
      },
      {
        to: "/experiments",
        label: "Experiments",
        icon: <FlaskConical size={18} />,
      },
      {
        to: "/demand",
        label: "Demand Signals",
        icon: <Radio size={18} />,
      },
    ],
  },
  {
    title: "INFRASTRUCTURE",
    items: [
      { to: "/vendors", label: "Vendors", icon: <Truck size={18} /> },
      { to: "/capital", label: "Capital", icon: <Vault size={18} /> },
      {
        to: "/compliance",
        label: "Compliance",
        icon: <Scale size={18} />,
      },
    ],
  },
  {
    title: "HISTORY",
    items: [
      { to: "/kill-log", label: "Kill Log", icon: <Skull size={18} /> },
    ],
  },
];

export function Sidebar() {
  return (
    <aside
      className="fixed left-0 top-0 bottom-0 w-64 flex flex-col"
      style={{ backgroundColor: "var(--bg-surface-1)" }}
    >
      {/* Title */}
      <div className="px-5 pt-6 pb-2">
        <h1
          className="text-lg tracking-wide"
          style={{
            fontFamily: "'Bricolage Grotesque', sans-serif",
            fontWeight: 700,
            color: "var(--accent)",
          }}
        >
          COMMERCE ENGINE
        </h1>
        <div className="flex items-center gap-2 mt-2">
          <span
            className="inline-block w-2 h-2 rounded-full"
            style={{
              backgroundColor: "var(--profit)",
              animation: "status-pulse 2s ease infinite",
            }}
          />
          <span
            className="text-xs"
            style={{
              fontFamily: "'Onest', sans-serif",
              fontWeight: 400,
              color: "var(--text-secondary)",
            }}
          >
            System Online
          </span>
        </div>
      </div>

      {/* Nav sections */}
      <nav className="flex-1 overflow-y-auto mt-4">
        {sections.map((section) => (
          <div key={section.title} className="mb-2">
            <div
              className="px-5 mb-1"
              style={{ borderTop: "1px solid var(--border-default)" }}
            />
            <div
              className="px-5 py-1.5"
              style={{
                fontFamily: "'Onest', sans-serif",
                fontWeight: 600,
                fontSize: 11,
                letterSpacing: "0.05em",
                color: "var(--text-tertiary)",
                textTransform: "uppercase",
              }}
            >
              {section.title}
            </div>
            {section.items.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.to === "/"}
                className={({ isActive }) =>
                  `flex items-center gap-3 px-5 py-2 transition-colors duration-150 ${
                    isActive ? "" : ""
                  }`
                }
                style={({ isActive }) => ({
                  fontFamily: "'Onest', sans-serif",
                  fontWeight: 500,
                  fontSize: 14,
                  color: isActive ? "var(--accent)" : "var(--text-secondary)",
                  backgroundColor: isActive
                    ? "var(--accent-dim)"
                    : "transparent",
                  borderLeft: isActive
                    ? "3px solid var(--accent)"
                    : "3px solid transparent",
                  textDecoration: "none",
                })}
                onMouseEnter={(e) => {
                  const el = e.currentTarget;
                  if (!el.classList.contains("active")) {
                    el.style.backgroundColor = "var(--bg-surface-3)";
                  }
                }}
                onMouseLeave={(e) => {
                  const el = e.currentTarget;
                  const isActive =
                    el.getAttribute("aria-current") === "page";
                  if (!isActive) {
                    el.style.backgroundColor = "transparent";
                  }
                }}
              >
                {item.icon}
                {item.label}
              </NavLink>
            ))}
          </div>
        ))}
      </nav>

      {/* Footer sprite */}
      <div className="px-5 py-4 flex justify-center">
        <svg
          width="20"
          height="20"
          viewBox="0 0 32 32"
          fill="none"
          className="sprite--walk"
          style={{ display: "inline-block" }}
        >
          {/* Hard hat */}
          <rect x="8" y="4" width="16" height="5" rx="2" fill="var(--accent)" />
          <rect x="6" y="8" width="20" height="2" rx="1" fill="var(--accent)" />
          {/* Head */}
          <circle cx="16" cy="14" r="5" fill="var(--text-primary)" />
          {/* Eyes */}
          <circle cx="14" cy="13" r="1" fill="var(--bg-root)" />
          <circle cx="18" cy="13" r="1" fill="var(--bg-root)" />
          {/* Body */}
          <rect x="12" y="19" width="8" height="6" rx="2" fill="var(--accent)" />
          {/* Legs */}
          <rect x="13" y="25" width="2" height="4" rx="1" fill="var(--text-secondary)" />
          <rect x="17" y="25" width="2" height="4" rx="1" fill="var(--text-secondary)" />
          {/* Clipboard */}
          <rect x="21" y="20" width="4" height="5" rx="1" fill="var(--text-secondary)" />
          <rect x="22" y="21" width="2" height="1" rx="0.5" fill="var(--bg-root)" />
          <rect x="22" y="23" width="2" height="1" rx="0.5" fill="var(--bg-root)" />
        </svg>
      </div>
    </aside>
  );
}
