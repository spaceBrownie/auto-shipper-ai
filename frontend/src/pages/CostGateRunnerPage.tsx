import { useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { StepIndicator } from "@/components/StepIndicator";
import { CostBreakdownTable } from "@/components/CostBreakdownTable";
import { StressTestResultCard } from "@/components/StressTestResultCard";
import { SpriteScene } from "@/components/sprites/SpriteScene";
import { Guard } from "@/components/sprites/Guard";
import {
  useSkus,
  useCreateSku,
  useVerifyCosts,
  useRunStressTest,
  useTransitionSku,
} from "@/api/skus";
import type {
  SkuResponse,
  CostEnvelopeResponse,
  StressTestResponse,
  VerifyCostsRequest,
} from "@/api/types";

const STEPS = ["Select SKU", "Verify Costs", "Stress Test", "Result"];

// ─────────────────────────────────────────────────────────────────
// MoneyInput — a paired amount + currency input
// ─────────────────────────────────────────────────────────────────
function MoneyInput({
  label,
  amount,
  currency,
  onAmountChange,
  onCurrencyChange,
}: {
  label: string;
  amount: string;
  currency: string;
  onAmountChange: (v: string) => void;
  onCurrencyChange: (v: string) => void;
}) {
  return (
    <div>
      <Label
        style={{
          fontFamily: "'Onest', sans-serif",
          fontWeight: 500,
          fontSize: 12,
          color: "var(--text-secondary)",
          marginBottom: 4,
        }}
      >
        {label}
      </Label>
      <div className="flex gap-2">
        <Input
          type="number"
          step="0.01"
          min="0"
          placeholder="0.00"
          value={amount}
          onChange={(e) => onAmountChange(e.target.value)}
          className="flex-1"
          style={{
            fontFamily: "'Martian Mono', monospace",
            fontSize: 13,
            backgroundColor: "var(--bg-surface-2)",
          }}
        />
        <Input
          type="text"
          placeholder="USD"
          value={currency}
          onChange={(e) => onCurrencyChange(e.target.value.toUpperCase())}
          className="w-20"
          style={{
            fontFamily: "'Martian Mono', monospace",
            fontSize: 13,
            backgroundColor: "var(--bg-surface-2)",
          }}
        />
      </div>
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────
// Section heading inside the form
// ─────────────────────────────────────────────────────────────────
function SectionHeading({ children }: { children: string }) {
  return (
    <h3
      className="mt-4 mb-2"
      style={{
        fontFamily: "'Bricolage Grotesque', sans-serif",
        fontWeight: 600,
        fontSize: 16,
        color: "var(--accent)",
      }}
    >
      {children}
    </h3>
  );
}

// ─────────────────────────────────────────────────────────────────
// Page
// ─────────────────────────────────────────────────────────────────
export default function CostGateRunnerPage() {
  const [step, setStep] = useState(0);

  // Step 1 — SKU selection
  const [selectedSkuId, setSelectedSkuId] = useState<string>("");
  const [selectedSku, setSelectedSku] = useState<SkuResponse | null>(null);
  const [newSkuName, setNewSkuName] = useState("");
  const [newSkuCategory, setNewSkuCategory] = useState("");
  const [createMode, setCreateMode] = useState(false);

  // Step 2 — cost verification form state
  const [vendorQuoteAmount, setVendorQuoteAmount] = useState("");
  const [vendorQuoteCurrency, setVendorQuoteCurrency] = useState("USD");
  const [packagingAmount, setPackagingAmount] = useState("");
  const [packagingCurrency, setPackagingCurrency] = useState("USD");
  const [returnHandlingAmount, setReturnHandlingAmount] = useState("");
  const [returnHandlingCurrency, setReturnHandlingCurrency] = useState("USD");
  const [pkgLength, setPkgLength] = useState("");
  const [pkgWidth, setPkgWidth] = useState("");
  const [pkgHeight, setPkgHeight] = useState("");
  const [pkgWeight, setPkgWeight] = useState("");
  const [originCountry, setOriginCountry] = useState("");
  const [originState, setOriginState] = useState("");
  const [originCity, setOriginCity] = useState("");
  const [originPostal, setOriginPostal] = useState("");
  const [destCountry, setDestCountry] = useState("");
  const [destState, setDestState] = useState("");
  const [destCity, setDestCity] = useState("");
  const [destPostal, setDestPostal] = useState("");
  const [cacAmount, setCacAmount] = useState("");
  const [cacCurrency, setCacCurrency] = useState("USD");
  const [refundRate, setRefundRate] = useState("5.0");
  const [chargebackRate, setChargebackRate] = useState("2.0");
  const [taxesAmount, setTaxesAmount] = useState("");
  const [taxesCurrency, setTaxesCurrency] = useState("USD");
  const [estOrderValueAmount, setEstOrderValueAmount] = useState("");
  const [estOrderValueCurrency, setEstOrderValueCurrency] = useState("USD");
  const [jurisdiction, setJurisdiction] = useState("");
  const [warehouseAmount, setWarehouseAmount] = useState("");
  const [warehouseCurrency, setWarehouseCurrency] = useState("USD");
  const [customerServiceAmount, setCustomerServiceAmount] = useState("");
  const [customerServiceCurrency, setCustomerServiceCurrency] = useState("USD");

  // Step 2 result
  const [costEnvelope, setCostEnvelope] = useState<CostEnvelopeResponse | null>(
    null,
  );

  // Step 3
  const [estPriceAmount, setEstPriceAmount] = useState("");
  const [estPriceCurrency, setEstPriceCurrency] = useState("USD");

  // Step 4
  const [stressResult, setStressResult] =
    useState<StressTestResponse | null>(null);

  // Queries & mutations
  const { data: costGatingSkus, isLoading: skusLoading } =
    useSkus("CostGating");
  const createSkuMutation = useCreateSku();
  const verifyCostsMutation = useVerifyCosts();
  const stressTestMutation = useRunStressTest();
  const transitionMutation = useTransitionSku();

  // ── Step 1 handlers ──────────────────────────────────────────
  const handleNextFromStep1 = async () => {
    if (createMode) {
      if (!newSkuName.trim() || !newSkuCategory.trim()) return;
      const created = await createSkuMutation.mutateAsync({
        name: newSkuName.trim(),
        category: newSkuCategory.trim(),
      });
      setSelectedSku(created);
      setSelectedSkuId(created.id);
    } else {
      if (!selectedSkuId) return;
      const sku =
        costGatingSkus?.find((s) => s.id === selectedSkuId) ?? null;
      setSelectedSku(sku);
    }
    setStep(1);
  };

  // ── Step 2 handlers ──────────────────────────────────────────
  const handleVerifyCosts = async () => {
    if (!selectedSku) return;

    const body: { id: string } & VerifyCostsRequest = {
      id: selectedSku.id,
      vendorQuote: {
        amount: vendorQuoteAmount,
        currency: vendorQuoteCurrency,
      },
      packageDimensions: {
        lengthCm: parseFloat(pkgLength) || 0,
        widthCm: parseFloat(pkgWidth) || 0,
        heightCm: parseFloat(pkgHeight) || 0,
        weightKg: parseFloat(pkgWeight) || 0,
      },
      originAddress: {
        country: originCountry,
        state: originState,
        city: originCity,
        postalCode: originPostal,
      },
      destinationAddress: {
        country: destCountry,
        state: destState,
        city: destCity,
        postalCode: destPostal,
      },
      cacEstimate: { amount: cacAmount, currency: cacCurrency },
      jurisdiction,
      warehousingCostPerUnit: {
        amount: warehouseAmount,
        currency: warehouseCurrency,
      },
      customerServiceCostPerUnit: {
        amount: customerServiceAmount,
        currency: customerServiceCurrency,
      },
      packagingCostPerUnit: {
        amount: packagingAmount,
        currency: packagingCurrency,
      },
      returnHandlingCostPerUnit: {
        amount: returnHandlingAmount,
        currency: returnHandlingCurrency,
      },
      refundAllowanceRate: parseFloat(refundRate) || 0,
      chargebackAllowanceRate: parseFloat(chargebackRate) || 0,
      taxesAndDuties: { amount: taxesAmount, currency: taxesCurrency },
      estimatedOrderValue: {
        amount: estOrderValueAmount,
        currency: estOrderValueCurrency,
      },
    };

    const envelope = await verifyCostsMutation.mutateAsync(body);
    setCostEnvelope(envelope);
    setStep(2);
  };

  // ── Step 3 handlers ──────────────────────────────────────────
  const handleRunStressTest = async () => {
    if (!selectedSku) return;
    const result = await stressTestMutation.mutateAsync({
      id: selectedSku.id,
      estimatedPrice: { amount: estPriceAmount, currency: estPriceCurrency },
    });
    setStressResult(result);
    setStep(3);
  };

  // ── Step 4 handlers ──────────────────────────────────────────
  const handleApproveToLaunch = async () => {
    if (!selectedSku) return;
    await transitionMutation.mutateAsync({
      id: selectedSku.id,
      targetState: "Listed",
    });
  };

  // ─────────────────────────────────────────────────────────────
  // Render
  // ─────────────────────────────────────────────────────────────
  return (
    <div className="space-y-8">
      {/* Page title */}
      <h1
        style={{
          fontFamily: "'Bricolage Grotesque', sans-serif",
          fontWeight: 700,
          fontSize: 28,
          lineHeight: 1.2,
          color: "var(--text-primary)",
        }}
      >
        Cost Gate Runner
      </h1>

      {/* Step indicator + Guard sprite */}
      <div className="flex items-start gap-6">
        <div className="flex-1">
          <StepIndicator steps={STEPS} currentStep={step} />
        </div>
        <div className="flex-shrink-0">
          {step === 0 && (
            <SpriteScene message="Halt! Verify your costs." animation="idle">
              <Guard size={48} />
            </SpriteScene>
          )}
          {step === 3 && stressResult?.passed && (
            <SpriteScene message="Gate open. Proceed." animation="celebrate">
              <Guard size={48} />
            </SpriteScene>
          )}
          {step === 3 && stressResult && !stressResult.passed && (
            <SpriteScene message="Gate stays closed." animation="alert">
              <Guard size={48} />
            </SpriteScene>
          )}
        </div>
      </div>

      {/* ── Step 1: Select SKU ──────────────────────────────── */}
      {step === 0 && (
        <Card
          style={{
            backgroundColor: "var(--bg-surface-1)",
            border: "1px solid var(--border-default)",
          }}
        >
          <CardHeader>
            <CardTitle
              style={{
                fontFamily: "'Bricolage Grotesque', sans-serif",
                fontWeight: 600,
                fontSize: 20,
                color: "var(--text-primary)",
              }}
            >
              Step 1: Select SKU
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-5">
            {!createMode ? (
              <>
                <div>
                  <Label
                    style={{
                      fontFamily: "'Onest', sans-serif",
                      fontWeight: 500,
                      fontSize: 12,
                      color: "var(--text-secondary)",
                      marginBottom: 6,
                    }}
                  >
                    Select existing SKU in CostGating state
                  </Label>
                  {skusLoading ? (
                    <Skeleton className="h-8 w-full" />
                  ) : costGatingSkus && costGatingSkus.length > 0 ? (
                    <Select
                      value={selectedSkuId}
                      onValueChange={(val) =>
                        setSelectedSkuId(val as string)
                      }
                    >
                      <SelectTrigger
                        className="w-full"
                        style={{ backgroundColor: "var(--bg-surface-2)" }}
                      >
                        <SelectValue placeholder="Choose a SKU..." />
                      </SelectTrigger>
                      <SelectContent>
                        {costGatingSkus.map((sku) => (
                          <SelectItem key={sku.id} value={sku.id}>
                            {sku.name} ({sku.category})
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  ) : (
                    <p
                      style={{
                        fontFamily: "'Onest', sans-serif",
                        fontSize: 14,
                        color: "var(--text-tertiary)",
                      }}
                    >
                      No SKUs in CostGating state. Create a new one below.
                    </p>
                  )}
                </div>

                <div
                  className="text-center py-2"
                  style={{
                    fontFamily: "'Onest', sans-serif",
                    fontSize: 14,
                    color: "var(--text-tertiary)",
                  }}
                >
                  &mdash; or &mdash;
                </div>

                <Button
                  variant="outline"
                  onClick={() => setCreateMode(true)}
                  style={{
                    fontFamily: "'Onest', sans-serif",
                    fontWeight: 500,
                  }}
                >
                  Create New SKU
                </Button>
              </>
            ) : (
              <div className="space-y-4">
                <div>
                  <Label
                    style={{
                      fontFamily: "'Onest', sans-serif",
                      fontWeight: 500,
                      fontSize: 12,
                      color: "var(--text-secondary)",
                      marginBottom: 4,
                    }}
                  >
                    SKU Name
                  </Label>
                  <Input
                    placeholder="e.g. Bamboo Yoga Mat"
                    value={newSkuName}
                    onChange={(e) => setNewSkuName(e.target.value)}
                    style={{ backgroundColor: "var(--bg-surface-2)" }}
                  />
                </div>
                <div>
                  <Label
                    style={{
                      fontFamily: "'Onest', sans-serif",
                      fontWeight: 500,
                      fontSize: 12,
                      color: "var(--text-secondary)",
                      marginBottom: 4,
                    }}
                  >
                    Category
                  </Label>
                  <Input
                    placeholder="e.g. Home & Garden"
                    value={newSkuCategory}
                    onChange={(e) => setNewSkuCategory(e.target.value)}
                    style={{ backgroundColor: "var(--bg-surface-2)" }}
                  />
                </div>
                <Button
                  variant="outline"
                  onClick={() => setCreateMode(false)}
                  style={{
                    fontFamily: "'Onest', sans-serif",
                    fontWeight: 500,
                  }}
                >
                  Back to Select
                </Button>
              </div>
            )}

            <div className="flex justify-end pt-4">
              <Button
                onClick={handleNextFromStep1}
                disabled={
                  createMode
                    ? !newSkuName.trim() ||
                      !newSkuCategory.trim() ||
                      createSkuMutation.isPending
                    : !selectedSkuId
                }
                style={{
                  backgroundColor: "var(--accent)",
                  color: "var(--bg-root)",
                  fontFamily: "'Onest', sans-serif",
                  fontWeight: 600,
                }}
              >
                {createSkuMutation.isPending ? "Creating..." : "Next Step"}
              </Button>
            </div>

            {createSkuMutation.isError && (
              <p
                style={{
                  color: "var(--danger)",
                  fontFamily: "'Onest', sans-serif",
                  fontSize: 13,
                }}
              >
                Failed to create SKU. Please try again.
              </p>
            )}
          </CardContent>
        </Card>
      )}

      {/* ── Step 2: Verify Costs ────────────────────────────── */}
      {step === 1 && (
        <Card
          style={{
            backgroundColor: "var(--bg-surface-1)",
            border: "1px solid var(--border-default)",
          }}
        >
          <CardHeader>
            <CardTitle
              style={{
                fontFamily: "'Bricolage Grotesque', sans-serif",
                fontWeight: 600,
                fontSize: 20,
                color: "var(--text-primary)",
              }}
            >
              Step 2: Verify Costs
              {selectedSku && (
                <span
                  className="ml-3"
                  style={{
                    fontFamily: "'Onest', sans-serif",
                    fontWeight: 400,
                    fontSize: 14,
                    color: "var(--text-secondary)",
                  }}
                >
                  for {selectedSku.name}
                </span>
              )}
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            {/* ── Product ── */}
            <SectionHeading>Product</SectionHeading>
            <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
              <MoneyInput
                label="Vendor Quote"
                amount={vendorQuoteAmount}
                currency={vendorQuoteCurrency}
                onAmountChange={setVendorQuoteAmount}
                onCurrencyChange={setVendorQuoteCurrency}
              />
              <MoneyInput
                label="Packaging Cost"
                amount={packagingAmount}
                currency={packagingCurrency}
                onAmountChange={setPackagingAmount}
                onCurrencyChange={setPackagingCurrency}
              />
              <MoneyInput
                label="Return Handling Cost"
                amount={returnHandlingAmount}
                currency={returnHandlingCurrency}
                onAmountChange={setReturnHandlingAmount}
                onCurrencyChange={setReturnHandlingCurrency}
              />
            </div>

            {/* ── Shipping ── */}
            <SectionHeading>Shipping</SectionHeading>
            <div>
              <Label
                style={{
                  fontFamily: "'Onest', sans-serif",
                  fontWeight: 500,
                  fontSize: 12,
                  color: "var(--text-secondary)",
                  marginBottom: 4,
                }}
              >
                Package Dimensions
              </Label>
              <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                <div>
                  <span
                    className="text-xs"
                    style={{
                      color: "var(--text-tertiary)",
                      fontFamily: "'Onest', sans-serif",
                    }}
                  >
                    Length (cm)
                  </span>
                  <Input
                    type="number"
                    step="0.1"
                    placeholder="0"
                    value={pkgLength}
                    onChange={(e) => setPkgLength(e.target.value)}
                    style={{
                      backgroundColor: "var(--bg-surface-2)",
                      fontFamily: "'Martian Mono', monospace",
                      fontSize: 13,
                    }}
                  />
                </div>
                <div>
                  <span
                    className="text-xs"
                    style={{
                      color: "var(--text-tertiary)",
                      fontFamily: "'Onest', sans-serif",
                    }}
                  >
                    Width (cm)
                  </span>
                  <Input
                    type="number"
                    step="0.1"
                    placeholder="0"
                    value={pkgWidth}
                    onChange={(e) => setPkgWidth(e.target.value)}
                    style={{
                      backgroundColor: "var(--bg-surface-2)",
                      fontFamily: "'Martian Mono', monospace",
                      fontSize: 13,
                    }}
                  />
                </div>
                <div>
                  <span
                    className="text-xs"
                    style={{
                      color: "var(--text-tertiary)",
                      fontFamily: "'Onest', sans-serif",
                    }}
                  >
                    Height (cm)
                  </span>
                  <Input
                    type="number"
                    step="0.1"
                    placeholder="0"
                    value={pkgHeight}
                    onChange={(e) => setPkgHeight(e.target.value)}
                    style={{
                      backgroundColor: "var(--bg-surface-2)",
                      fontFamily: "'Martian Mono', monospace",
                      fontSize: 13,
                    }}
                  />
                </div>
                <div>
                  <span
                    className="text-xs"
                    style={{
                      color: "var(--text-tertiary)",
                      fontFamily: "'Onest', sans-serif",
                    }}
                  >
                    Weight (kg)
                  </span>
                  <Input
                    type="number"
                    step="0.01"
                    placeholder="0"
                    value={pkgWeight}
                    onChange={(e) => setPkgWeight(e.target.value)}
                    style={{
                      backgroundColor: "var(--bg-surface-2)",
                      fontFamily: "'Martian Mono', monospace",
                      fontSize: 13,
                    }}
                  />
                </div>
              </div>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-6 mt-3">
              {/* Origin */}
              <div className="space-y-2">
                <Label
                  style={{
                    fontFamily: "'Onest', sans-serif",
                    fontWeight: 500,
                    fontSize: 12,
                    color: "var(--text-secondary)",
                  }}
                >
                  Origin Address
                </Label>
                <div className="grid grid-cols-2 gap-2">
                  <Input
                    placeholder="Country"
                    value={originCountry}
                    onChange={(e) => setOriginCountry(e.target.value)}
                    style={{
                      backgroundColor: "var(--bg-surface-2)",
                      fontSize: 13,
                    }}
                  />
                  <Input
                    placeholder="State"
                    value={originState}
                    onChange={(e) => setOriginState(e.target.value)}
                    style={{
                      backgroundColor: "var(--bg-surface-2)",
                      fontSize: 13,
                    }}
                  />
                  <Input
                    placeholder="City"
                    value={originCity}
                    onChange={(e) => setOriginCity(e.target.value)}
                    style={{
                      backgroundColor: "var(--bg-surface-2)",
                      fontSize: 13,
                    }}
                  />
                  <Input
                    placeholder="Postal Code"
                    value={originPostal}
                    onChange={(e) => setOriginPostal(e.target.value)}
                    style={{
                      backgroundColor: "var(--bg-surface-2)",
                      fontSize: 13,
                    }}
                  />
                </div>
              </div>
              {/* Destination */}
              <div className="space-y-2">
                <Label
                  style={{
                    fontFamily: "'Onest', sans-serif",
                    fontWeight: 500,
                    fontSize: 12,
                    color: "var(--text-secondary)",
                  }}
                >
                  Destination Address
                </Label>
                <div className="grid grid-cols-2 gap-2">
                  <Input
                    placeholder="Country"
                    value={destCountry}
                    onChange={(e) => setDestCountry(e.target.value)}
                    style={{
                      backgroundColor: "var(--bg-surface-2)",
                      fontSize: 13,
                    }}
                  />
                  <Input
                    placeholder="State"
                    value={destState}
                    onChange={(e) => setDestState(e.target.value)}
                    style={{
                      backgroundColor: "var(--bg-surface-2)",
                      fontSize: 13,
                    }}
                  />
                  <Input
                    placeholder="City"
                    value={destCity}
                    onChange={(e) => setDestCity(e.target.value)}
                    style={{
                      backgroundColor: "var(--bg-surface-2)",
                      fontSize: 13,
                    }}
                  />
                  <Input
                    placeholder="Postal Code"
                    value={destPostal}
                    onChange={(e) => setDestPostal(e.target.value)}
                    style={{
                      backgroundColor: "var(--bg-surface-2)",
                      fontSize: 13,
                    }}
                  />
                </div>
              </div>
            </div>

            {/* ── Fees ── */}
            <SectionHeading>Fees</SectionHeading>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <MoneyInput
                label="CAC Estimate"
                amount={cacAmount}
                currency={cacCurrency}
                onAmountChange={setCacAmount}
                onCurrencyChange={setCacCurrency}
              />
              <div>
                <Label
                  style={{
                    fontFamily: "'Onest', sans-serif",
                    fontWeight: 500,
                    fontSize: 12,
                    color: "var(--text-secondary)",
                    marginBottom: 4,
                  }}
                >
                  Refund Allowance Rate (%)
                </Label>
                <Input
                  type="number"
                  step="0.1"
                  min="0"
                  max="100"
                  value={refundRate}
                  onChange={(e) => setRefundRate(e.target.value)}
                  style={{
                    backgroundColor: "var(--bg-surface-2)",
                    fontFamily: "'Martian Mono', monospace",
                    fontSize: 13,
                  }}
                />
              </div>
              <div>
                <Label
                  style={{
                    fontFamily: "'Onest', sans-serif",
                    fontWeight: 500,
                    fontSize: 12,
                    color: "var(--text-secondary)",
                    marginBottom: 4,
                  }}
                >
                  Chargeback Allowance Rate (%)
                </Label>
                <Input
                  type="number"
                  step="0.1"
                  min="0"
                  max="100"
                  value={chargebackRate}
                  onChange={(e) => setChargebackRate(e.target.value)}
                  style={{
                    backgroundColor: "var(--bg-surface-2)",
                    fontFamily: "'Martian Mono', monospace",
                    fontSize: 13,
                  }}
                />
              </div>
              <MoneyInput
                label="Taxes & Duties"
                amount={taxesAmount}
                currency={taxesCurrency}
                onAmountChange={setTaxesAmount}
                onCurrencyChange={setTaxesCurrency}
              />
              <MoneyInput
                label="Estimated Order Value"
                amount={estOrderValueAmount}
                currency={estOrderValueCurrency}
                onAmountChange={setEstOrderValueAmount}
                onCurrencyChange={setEstOrderValueCurrency}
              />
            </div>

            {/* ── Operations ── */}
            <SectionHeading>Operations</SectionHeading>
            <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
              <div>
                <Label
                  style={{
                    fontFamily: "'Onest', sans-serif",
                    fontWeight: 500,
                    fontSize: 12,
                    color: "var(--text-secondary)",
                    marginBottom: 4,
                  }}
                >
                  Jurisdiction
                </Label>
                <Input
                  placeholder="e.g. US"
                  value={jurisdiction}
                  onChange={(e) => setJurisdiction(e.target.value)}
                  style={{
                    backgroundColor: "var(--bg-surface-2)",
                    fontSize: 13,
                  }}
                />
              </div>
              <MoneyInput
                label="Warehouse Cost / Unit"
                amount={warehouseAmount}
                currency={warehouseCurrency}
                onAmountChange={setWarehouseAmount}
                onCurrencyChange={setWarehouseCurrency}
              />
              <MoneyInput
                label="Customer Service Cost / Unit"
                amount={customerServiceAmount}
                currency={customerServiceCurrency}
                onAmountChange={setCustomerServiceAmount}
                onCurrencyChange={setCustomerServiceCurrency}
              />
            </div>

            {/* Submit */}
            <div className="flex justify-between items-center pt-6">
              <Button
                variant="outline"
                onClick={() => setStep(0)}
                style={{
                  fontFamily: "'Onest', sans-serif",
                  fontWeight: 500,
                }}
              >
                Back
              </Button>
              <Button
                onClick={handleVerifyCosts}
                disabled={
                  verifyCostsMutation.isPending || !vendorQuoteAmount
                }
                style={{
                  backgroundColor: "var(--accent)",
                  color: "var(--bg-root)",
                  fontFamily: "'Onest', sans-serif",
                  fontWeight: 600,
                }}
              >
                {verifyCostsMutation.isPending
                  ? "Verifying..."
                  : "Verify Costs"}
              </Button>
            </div>

            {verifyCostsMutation.isError && (
              <p
                style={{
                  color: "var(--danger)",
                  fontFamily: "'Onest', sans-serif",
                  fontSize: 13,
                  marginTop: 8,
                }}
              >
                Cost verification failed. Check your inputs and try again.
              </p>
            )}
          </CardContent>
        </Card>
      )}

      {/* ── Step 3: Stress Test ─────────────────────────────── */}
      {step === 2 && costEnvelope && (
        <Card
          style={{
            backgroundColor: "var(--bg-surface-1)",
            border: "1px solid var(--border-default)",
          }}
        >
          <CardHeader>
            <CardTitle
              style={{
                fontFamily: "'Bricolage Grotesque', sans-serif",
                fontWeight: 600,
                fontSize: 20,
                color: "var(--text-primary)",
              }}
            >
              Step 3: Stress Test
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-6">
            <div>
              <h4
                className="mb-3"
                style={{
                  fontFamily: "'Onest', sans-serif",
                  fontWeight: 500,
                  fontSize: 14,
                  color: "var(--text-secondary)",
                }}
              >
                Verified Cost Envelope
              </h4>
              <CostBreakdownTable envelope={costEnvelope} />
            </div>

            <div className="max-w-sm">
              <MoneyInput
                label="Estimated Selling Price"
                amount={estPriceAmount}
                currency={estPriceCurrency}
                onAmountChange={setEstPriceAmount}
                onCurrencyChange={setEstPriceCurrency}
              />
            </div>

            <div className="flex justify-between items-center pt-4">
              <Button
                variant="outline"
                onClick={() => setStep(1)}
                style={{
                  fontFamily: "'Onest', sans-serif",
                  fontWeight: 500,
                }}
              >
                Back
              </Button>
              <Button
                onClick={handleRunStressTest}
                disabled={
                  stressTestMutation.isPending || !estPriceAmount
                }
                style={{
                  backgroundColor: "var(--accent)",
                  color: "var(--bg-root)",
                  fontFamily: "'Onest', sans-serif",
                  fontWeight: 600,
                }}
              >
                {stressTestMutation.isPending
                  ? "Running..."
                  : "Run Stress Test"}
              </Button>
            </div>

            {stressTestMutation.isError && (
              <p
                style={{
                  color: "var(--danger)",
                  fontFamily: "'Onest', sans-serif",
                  fontSize: 13,
                }}
              >
                Stress test failed. Please try again.
              </p>
            )}
          </CardContent>
        </Card>
      )}

      {/* ── Step 4: Result ──────────────────────────────────── */}
      {step === 3 && stressResult && (
        <Card
          style={{
            backgroundColor: "var(--bg-surface-1)",
            border: "1px solid var(--border-default)",
          }}
        >
          <CardHeader>
            <CardTitle
              style={{
                fontFamily: "'Bricolage Grotesque', sans-serif",
                fontWeight: 600,
                fontSize: 20,
                color: "var(--text-primary)",
              }}
            >
              Step 4: Result
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-6">
            <StressTestResultCard result={stressResult} />

            {stressResult.passed ? (
              <div className="flex items-center gap-4 pt-2">
                <Button
                  onClick={handleApproveToLaunch}
                  disabled={transitionMutation.isPending}
                  style={{
                    backgroundColor: "var(--profit)",
                    color: "var(--bg-root)",
                    fontFamily: "'Onest', sans-serif",
                    fontWeight: 600,
                  }}
                >
                  {transitionMutation.isPending
                    ? "Transitioning..."
                    : "Approve to Launch"}
                </Button>
                {transitionMutation.isSuccess && (
                  <span
                    style={{
                      fontFamily: "'Onest', sans-serif",
                      fontSize: 14,
                      color: "var(--profit)",
                    }}
                  >
                    SKU is now Listed.
                  </span>
                )}
              </div>
            ) : (
              <div
                className="rounded-md px-4 py-3"
                style={{
                  backgroundColor: "var(--danger-dim)",
                  fontFamily: "'Onest', sans-serif",
                  fontSize: 14,
                  color: "var(--danger)",
                }}
              >
                SKU has been terminated. The stress test margins did not meet
                the required thresholds (gross &ge; 50%, net &ge; 30%).
              </div>
            )}

            {transitionMutation.isError && (
              <p
                style={{
                  color: "var(--danger)",
                  fontFamily: "'Onest', sans-serif",
                  fontSize: 13,
                }}
              >
                Failed to transition SKU. Please try again.
              </p>
            )}
          </CardContent>
        </Card>
      )}
    </div>
  );
}
