import { useState } from "react";
import {
  useExperiments,
  useCreateExperiment,
  useValidateExperiment,
  useFailExperiment,
} from "@/api/portfolio";
import { DataTable, type Column } from "@/components/DataTable";
import { StatusBadge } from "@/components/StatusBadge";
import { SpriteScene } from "@/components/sprites/SpriteScene";
import { Scout } from "@/components/sprites/Scout";
import { formatMoney, daysRemaining } from "@/lib/formatters";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import type { ExperimentResponse } from "@/api/types";

export default function ExperimentTrackerPage() {
  const { data: experiments, isLoading } = useExperiments();
  const createExperiment = useCreateExperiment();
  const validateExperiment = useValidateExperiment();
  const failExperiment = useFailExperiment();

  const [createOpen, setCreateOpen] = useState(false);
  const [validateOpen, setValidateOpen] = useState(false);
  const [validateExpId, setValidateExpId] = useState("");
  const [validateSkuId, setValidateSkuId] = useState("");

  // Create form state
  const [name, setName] = useState("");
  const [hypothesis, setHypothesis] = useState("");
  const [sourceSignal, setSourceSignal] = useState("");
  const [marginAmount, setMarginAmount] = useState("");
  const [marginCurrency, setMarginCurrency] = useState("USD");
  const [windowDays, setWindowDays] = useState("30");

  function resetCreateForm() {
    setName("");
    setHypothesis("");
    setSourceSignal("");
    setMarginAmount("");
    setMarginCurrency("USD");
    setWindowDays("30");
  }

  function handleCreate() {
    const body: Parameters<typeof createExperiment.mutate>[0] = {
      name,
      hypothesis,
      validationWindowDays: parseInt(windowDays, 10) || 30,
    };
    if (sourceSignal.trim()) {
      body.sourceSignal = sourceSignal.trim();
    }
    if (marginAmount.trim()) {
      body.estimatedMarginPerUnit = {
        amount: marginAmount.trim(),
        currency: marginCurrency,
      };
    }
    createExperiment.mutate(body, {
      onSuccess: () => {
        setCreateOpen(false);
        resetCreateForm();
      },
    });
  }

  function handleValidate() {
    if (!validateExpId || !validateSkuId.trim()) return;
    validateExperiment.mutate(
      { id: validateExpId, skuId: validateSkuId.trim() },
      {
        onSuccess: () => {
          setValidateOpen(false);
          setValidateSkuId("");
          setValidateExpId("");
        },
      },
    );
  }

  function handleFail(id: string) {
    failExperiment.mutate(id);
  }

  const columns: Column<ExperimentResponse>[] = [
    { key: "name", header: "Name" },
    { key: "hypothesis", header: "Hypothesis" },
    {
      key: "sourceSignal",
      header: "Source Signal",
      render: (v: string | null) => v ?? "--",
    },
    {
      key: "estimatedMarginPerUnit",
      header: "Est. Margin/Unit",
      render: (_v: number | null, row: ExperimentResponse) =>
        row.estimatedMarginPerUnit != null && row.estimatedMarginCurrency
          ? formatMoney(row.estimatedMarginPerUnit, row.estimatedMarginCurrency)
          : "--",
    },
    {
      key: "status",
      header: "Status",
      render: (v: string) => <StatusBadge status={v} />,
    },
    {
      key: "validationWindowDays",
      header: "Days Left",
      render: (_v: number, row: ExperimentResponse) => {
        const remaining = daysRemaining(
          row.createdAt,
          row.validationWindowDays,
        );
        return (
          <span
            style={{
              fontFamily: "'Martian Mono', monospace",
              color:
                remaining <= 3
                  ? "var(--danger)"
                  : remaining <= 7
                    ? "var(--warning)"
                    : "var(--text-primary)",
            }}
          >
            {remaining}
          </span>
        );
      },
    },
    {
      key: "id",
      header: "Actions",
      render: (_v: string, row: ExperimentResponse) => {
        if (row.status !== "ACTIVE") return null;
        return (
          <div className="flex items-center gap-2">
            <Button
              size="sm"
              variant="outline"
              onClick={(e) => {
                e.stopPropagation();
                setValidateExpId(row.id);
                setValidateOpen(true);
              }}
              style={{
                borderColor: "var(--profit)",
                color: "var(--profit)",
                fontSize: 12,
              }}
            >
              Validate
            </Button>
            <Button
              size="sm"
              variant="outline"
              onClick={(e) => {
                e.stopPropagation();
                handleFail(row.id);
              }}
              disabled={failExperiment.isPending}
              style={{
                borderColor: "var(--danger)",
                color: "var(--danger)",
                fontSize: 12,
              }}
            >
              Fail
            </Button>
          </div>
        );
      },
    },
  ];

  return (
    <div>
      {/* Page title + create button */}
      <div className="flex items-center justify-between mb-8">
        <h1
          style={{
            fontFamily: "'Bricolage Grotesque', sans-serif",
            fontWeight: 700,
            fontSize: 28,
            lineHeight: 1.2,
            color: "var(--text-primary)",
          }}
        >
          Experiment Tracker
        </h1>

        <Dialog open={createOpen} onOpenChange={setCreateOpen}>
          <DialogTrigger
            render={
              <Button
                style={{
                  backgroundColor: "var(--accent)",
                  color: "var(--bg-root)",
                  fontFamily: "'Onest', sans-serif",
                  fontWeight: 600,
                }}
              />
            }
          >
            Create Experiment
          </DialogTrigger>
          <DialogContent
            style={{
              backgroundColor: "var(--bg-surface-2)",
              border: "1px solid var(--border-bright)",
            }}
          >
            <DialogHeader>
              <DialogTitle
                style={{
                  fontFamily: "'Bricolage Grotesque', sans-serif",
                  fontWeight: 600,
                  fontSize: 20,
                  color: "var(--text-primary)",
                }}
              >
                Create Experiment
              </DialogTitle>
            </DialogHeader>

            <div className="flex flex-col gap-4 mt-2">
              <div>
                <Label
                  style={{
                    fontFamily: "'Onest', sans-serif",
                    fontSize: 12,
                    color: "var(--text-secondary)",
                  }}
                >
                  Name
                </Label>
                <Input
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="e.g. Bamboo Kitchen Set"
                  style={{
                    backgroundColor: "var(--bg-surface-2)",
                    borderColor: "var(--border-default)",
                    color: "var(--text-primary)",
                  }}
                />
              </div>

              <div>
                <Label
                  style={{
                    fontFamily: "'Onest', sans-serif",
                    fontSize: 12,
                    color: "var(--text-secondary)",
                  }}
                >
                  Hypothesis
                </Label>
                <Input
                  value={hypothesis}
                  onChange={(e) => setHypothesis(e.target.value)}
                  placeholder="e.g. Eco-friendly kitchen items have high WTP"
                  style={{
                    backgroundColor: "var(--bg-surface-2)",
                    borderColor: "var(--border-default)",
                    color: "var(--text-primary)",
                  }}
                />
              </div>

              <div>
                <Label
                  style={{
                    fontFamily: "'Onest', sans-serif",
                    fontSize: 12,
                    color: "var(--text-secondary)",
                  }}
                >
                  Source Signal (optional)
                </Label>
                <Input
                  value={sourceSignal}
                  onChange={(e) => setSourceSignal(e.target.value)}
                  placeholder="e.g. Google Trends, Reddit, manual"
                  style={{
                    backgroundColor: "var(--bg-surface-2)",
                    borderColor: "var(--border-default)",
                    color: "var(--text-primary)",
                  }}
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <Label
                    style={{
                      fontFamily: "'Onest', sans-serif",
                      fontSize: 12,
                      color: "var(--text-secondary)",
                    }}
                  >
                    Est. Margin/Unit (optional)
                  </Label>
                  <Input
                    type="number"
                    step="0.01"
                    value={marginAmount}
                    onChange={(e) => setMarginAmount(e.target.value)}
                    placeholder="5.00"
                    style={{
                      backgroundColor: "var(--bg-surface-2)",
                      borderColor: "var(--border-default)",
                      color: "var(--text-primary)",
                      fontFamily: "'Martian Mono', monospace",
                    }}
                  />
                </div>
                <div>
                  <Label
                    style={{
                      fontFamily: "'Onest', sans-serif",
                      fontSize: 12,
                      color: "var(--text-secondary)",
                    }}
                  >
                    Currency
                  </Label>
                  <Input
                    value={marginCurrency}
                    onChange={(e) => setMarginCurrency(e.target.value)}
                    placeholder="USD"
                    style={{
                      backgroundColor: "var(--bg-surface-2)",
                      borderColor: "var(--border-default)",
                      color: "var(--text-primary)",
                    }}
                  />
                </div>
              </div>

              <div>
                <Label
                  style={{
                    fontFamily: "'Onest', sans-serif",
                    fontSize: 12,
                    color: "var(--text-secondary)",
                  }}
                >
                  Validation Window (days)
                </Label>
                <Input
                  type="number"
                  value={windowDays}
                  onChange={(e) => setWindowDays(e.target.value)}
                  placeholder="30"
                  style={{
                    backgroundColor: "var(--bg-surface-2)",
                    borderColor: "var(--border-default)",
                    color: "var(--text-primary)",
                    fontFamily: "'Martian Mono', monospace",
                  }}
                />
              </div>

              <Button
                onClick={handleCreate}
                disabled={
                  !name.trim() ||
                  !hypothesis.trim() ||
                  createExperiment.isPending
                }
                style={{
                  backgroundColor: "var(--accent)",
                  color: "var(--bg-root)",
                  fontFamily: "'Onest', sans-serif",
                  fontWeight: 600,
                  marginTop: 4,
                }}
              >
                {createExperiment.isPending ? "Creating..." : "Create"}
              </Button>
            </div>
          </DialogContent>
        </Dialog>
      </div>

      {/* Validate dialog */}
      <Dialog open={validateOpen} onOpenChange={setValidateOpen}>
        <DialogContent
          style={{
            backgroundColor: "var(--bg-surface-2)",
            border: "1px solid var(--border-bright)",
          }}
        >
          <DialogHeader>
            <DialogTitle
              style={{
                fontFamily: "'Bricolage Grotesque', sans-serif",
                fontWeight: 600,
                fontSize: 20,
                color: "var(--text-primary)",
              }}
            >
              Validate Experiment
            </DialogTitle>
          </DialogHeader>
          <div className="flex flex-col gap-4 mt-2">
            <div>
              <Label
                style={{
                  fontFamily: "'Onest', sans-serif",
                  fontSize: 12,
                  color: "var(--text-secondary)",
                }}
              >
                SKU ID to link
              </Label>
              <Input
                value={validateSkuId}
                onChange={(e) => setValidateSkuId(e.target.value)}
                placeholder="SKU UUID"
                style={{
                  backgroundColor: "var(--bg-surface-2)",
                  borderColor: "var(--border-default)",
                  color: "var(--text-primary)",
                  fontFamily: "'Martian Mono', monospace",
                }}
              />
            </div>
            <Button
              onClick={handleValidate}
              disabled={!validateSkuId.trim() || validateExperiment.isPending}
              style={{
                backgroundColor: "var(--profit)",
                color: "var(--bg-root)",
                fontFamily: "'Onest', sans-serif",
                fontWeight: 600,
              }}
            >
              {validateExperiment.isPending
                ? "Validating..."
                : "Confirm Validation"}
            </Button>
          </div>
        </DialogContent>
      </Dialog>

      {/* Content */}
      {isLoading ? (
        <div className="flex flex-col gap-3">
          <Skeleton
            className="h-10 w-full"
            style={{ backgroundColor: "var(--bg-surface-3)" }}
          />
          <Skeleton
            className="h-10 w-full"
            style={{ backgroundColor: "var(--bg-surface-3)" }}
          />
          <Skeleton
            className="h-10 w-full"
            style={{ backgroundColor: "var(--bg-surface-3)" }}
          />
        </div>
      ) : !experiments || experiments.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-24">
          <SpriteScene
            message="No experiments running. Go find some demand!"
            animation="idle"
          >
            <Scout size={48} />
          </SpriteScene>
        </div>
      ) : (
        <div
          className="rounded-lg overflow-hidden"
          style={{
            backgroundColor: "var(--bg-surface-1)",
            border: "1px solid var(--border-default)",
          }}
        >
          <DataTable columns={columns} data={experiments} />
        </div>
      )}
    </div>
  );
}
