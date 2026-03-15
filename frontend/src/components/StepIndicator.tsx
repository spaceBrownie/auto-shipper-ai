import { Check } from "lucide-react";

interface StepIndicatorProps {
  steps: string[];
  currentStep: number;
}

export function StepIndicator({ steps, currentStep }: StepIndicatorProps) {
  return (
    <div className="flex items-center w-full">
      {steps.map((step, i) => {
        const isCompleted = i < currentStep;
        const isActive = i === currentStep;
        const dotColor = isCompleted
          ? "var(--profit)"
          : isActive
          ? "var(--accent)"
          : "var(--text-tertiary)";

        const textColor = isCompleted
          ? "var(--profit)"
          : isActive
          ? "var(--accent)"
          : "var(--text-tertiary)";

        return (
          <div key={i} className="flex items-center flex-1 last:flex-none">
            {/* Dot + label */}
            <div className="flex flex-col items-center">
              <div
                className="w-7 h-7 rounded-full flex items-center justify-center"
                style={{
                  backgroundColor: isCompleted
                    ? "var(--profit-dim)"
                    : isActive
                    ? "var(--accent-dim)"
                    : "var(--bg-surface-3)",
                  border: `2px solid ${dotColor}`,
                }}
              >
                {isCompleted ? (
                  <Check size={14} style={{ color: "var(--profit)" }} />
                ) : (
                  <span
                    style={{
                      fontFamily: "'Martian Mono', monospace",
                      fontSize: 11,
                      color: dotColor,
                    }}
                  >
                    {i + 1}
                  </span>
                )}
              </div>
              <span
                className="mt-1.5 text-center whitespace-nowrap"
                style={{
                  fontFamily: "'Onest', sans-serif",
                  fontWeight: isActive ? 600 : 400,
                  fontSize: 12,
                  color: textColor,
                }}
              >
                {step}
              </span>
            </div>

            {/* Connector line */}
            {i < steps.length - 1 && (
              <div
                className="flex-1 mx-2"
                style={{
                  height: 2,
                  backgroundColor: isCompleted
                    ? "var(--profit)"
                    : "var(--border-default)",
                  marginBottom: 20, // offset for label below dot
                }}
              />
            )}
          </div>
        );
      })}
    </div>
  );
}
