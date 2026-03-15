// ──────────────────────────────────────────────
// Shared primitives
// ──────────────────────────────────────────────

export interface MoneyDto {
  amount: string;
  currency: string;
}

export interface PackageDimensionsDto {
  lengthCm: number;
  widthCm: number;
  heightCm: number;
  weightKg: number;
}

export interface AddressDto {
  country: string;
  state: string;
  city: string;
  postalCode: string;
}

// ──────────────────────────────────────────────
// Catalog — SKU
// ──────────────────────────────────────────────

export interface SkuResponse {
  id: string;
  name: string;
  category: string;
  currentState: string;
  terminationReason: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateSkuRequest {
  name: string;
  category: string;
}

export interface TransitionSkuRequest {
  targetState: string;
  reason?: string;
}

// ──────────────────────────────────────────────
// Catalog — Cost Envelope
// ──────────────────────────────────────────────

export interface CostEnvelopeResponse {
  skuId: string;
  currency: string;
  supplierUnitCost: number;
  inboundShipping: number;
  outboundShipping: number;
  platformFee: number;
  processingFee: number;
  packagingCost: number;
  returnHandlingCost: number;
  customerAcquisitionCost: number;
  warehousingCost: number;
  customerServiceCost: number;
  refundAllowance: number;
  chargebackAllowance: number;
  taxesAndDuties: number;
  fullyBurdened: number;
  verifiedAt: string;
}

export interface VerifyCostsRequest {
  vendorQuote: MoneyDto;
  packageDimensions: PackageDimensionsDto;
  originAddress: AddressDto;
  destinationAddress: AddressDto;
  cacEstimate: MoneyDto;
  jurisdiction: string;
  warehousingCostPerUnit: MoneyDto;
  customerServiceCostPerUnit: MoneyDto;
  packagingCostPerUnit: MoneyDto;
  returnHandlingCostPerUnit: MoneyDto;
  refundAllowanceRate: number;
  chargebackAllowanceRate: number;
  taxesAndDuties: MoneyDto;
  estimatedOrderValue: MoneyDto;
}

// ──────────────────────────────────────────────
// Catalog — Stress Test
// ──────────────────────────────────────────────

export interface StressTestRequest {
  estimatedPrice: MoneyDto;
}

export interface StressTestResponse {
  skuId: string;
  passed: boolean;
  grossMarginPercent: number;
  netMarginPercent: number;
  stressedTotalCost: number;
  estimatedPrice: number;
  stressedShipping: number;
  stressedCac: number;
  stressedSupplier: number;
  stressedRefund: number;
  stressedChargeback: number;
  currency: string;
}

// ──────────────────────────────────────────────
// Pricing
// ──────────────────────────────────────────────

export interface PricingResponse {
  skuId: string;
  currency: string;
  currentPrice: number;
  currentMarginPercent: number;
  updatedAt: string;
  history: PricingHistoryEntry[];
}

export interface PricingHistoryEntry {
  price: number;
  marginPercent: number;
  signalType: string;
  decisionType: string;
  decisionReason: string | null;
  recordedAt: string;
}

// ──────────────────────────────────────────────
// Vendor
// ──────────────────────────────────────────────

export interface VendorResponse {
  id: string;
  name: string;
  contactEmail: string;
  status: string;
  checklist: ChecklistResponse;
  createdAt: string;
  updatedAt: string;
}

export interface ChecklistResponse {
  slaConfirmed: boolean;
  defectRateDocumented: boolean;
  scalabilityConfirmed: boolean;
  fulfillmentTimesConfirmed: boolean;
  refundPolicyConfirmed: boolean;
}

export interface RegisterVendorRequest {
  name: string;
  contactEmail: string;
}

export interface UpdateChecklistRequest {
  slaConfirmed: boolean;
  defectRateDocumented: boolean;
  scalabilityConfirmed: boolean;
  fulfillmentTimesConfirmed: boolean;
  refundPolicyConfirmed: boolean;
}

export interface ComputeScoreRequest {
  onTimeRate: number;
  defectRate: number;
  breachCount: number;
  avgResponseTimeHours: number;
}

export interface VendorScoreResponse {
  overallScore: number;
  onTimeRate: number;
  defectRate: number;
  breachCount: number;
  avgResponseTimeHours: number;
}

// ──────────────────────────────────────────────
// Capital
// ──────────────────────────────────────────────

export interface ReserveResponse {
  balanceAmount: string;
  balanceCurrency: string;
  health: string;
}

export interface SkuPnlResponse {
  skuId: string;
  from: string;
  to: string;
  totalRevenueAmount: string;
  totalRevenueCurrency: string;
  totalCostAmount: string;
  totalCostCurrency: string;
  averageGrossMarginPercent: string;
  averageNetMarginPercent: string;
  snapshotCount: number;
}

export interface MarginSnapshotResponse {
  snapshotDate: string;
  grossMarginPercent: number;
  netMarginPercent: number;
  refundRate: number;
  chargebackRate: number;
}

// ──────────────────────────────────────────────
// Portfolio
// ──────────────────────────────────────────────

export interface PortfolioSummaryResponse {
  totalExperiments: number;
  activeExperiments: number;
  activeSkus: number;
  terminatedSkus: number;
  blendedNetMargin: number;
  totalProfit: number;
}

export interface ExperimentResponse {
  id: string;
  name: string;
  hypothesis: string;
  sourceSignal: string | null;
  estimatedMarginPerUnit: number | null;
  estimatedMarginCurrency: string | null;
  validationWindowDays: number;
  status: string;
  launchedSkuId: string | null;
  createdAt: string;
}

export interface CreateExperimentRequest {
  name: string;
  hypothesis: string;
  sourceSignal?: string;
  estimatedMarginPerUnit?: MoneyDto;
  validationWindowDays: number;
}

export interface ValidateExperimentRequest {
  skuId: string;
}

export interface KillRecommendationResponse {
  id: string;
  skuId: string;
  daysNegative: number;
  avgNetMargin: number;
  detectedAt: string;
  confirmedAt: string | null;
}

export interface PriorityRankingResponse {
  skuId: string;
  avgNetMargin: number;
  revenueVolume: number;
  riskFactor: number;
  riskAdjustedReturn: number;
}

export interface RefundAlertResponse {
  skuIds: string[];
  portfolioAvgRefundRate: number;
  elevatedSkuCount: number;
}

// ──────────────────────────────────────────────
// Compliance
// ──────────────────────────────────────────────

export interface ComplianceStatusResponse {
  skuId: string;
  latestResult: string;
  latestReason: string | null;
  auditHistory: AuditEntry[];
}

export interface AuditEntry {
  checkType: string;
  result: string;
  reason: string | null;
  detail: string | null;
  checkedAt: string;
}

export interface ManualCheckRequest {
  skuId: string;
}

// ──────────────────────────────────────────────
// Fulfillment
// ──────────────────────────────────────────────

export interface OrderResponse {
  id: string;
  skuId: string;
  vendorId: string;
  customerId: string;
  totalAmount: string;
  totalCurrency: string;
  status: string;
  trackingNumber: string | null;
  carrier: string | null;
  estimatedDelivery: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TrackingResponse {
  orderId: string;
  trackingNumber: string | null;
  carrier: string | null;
  estimatedDelivery: string | null;
  lastKnownLocation: string | null;
  delayDetected: boolean;
  status: string;
}

export interface CreateOrderRequest {
  skuId: string;
  vendorId: string;
  customerId: string;
  totalAmount: MoneyDto;
}

// ──────────────────────────────────────────────
// SKU State History
// ──────────────────────────────────────────────

export interface SkuStateHistoryEntry {
  fromState: string;
  toState: string;
  transitionedAt: string;
}
