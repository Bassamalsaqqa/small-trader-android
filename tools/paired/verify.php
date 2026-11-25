<?php

declare(strict_types=1);

require __DIR__.'/boot.php';
use App\Models\{
    Company,
    CompanyMobileSetting,
    CompanyUser,
    CustomerPayment,
    InventoryBalance,
    InventoryCostState,
    MobileEventReceipt,
    PostingBatch,
    Product,
    SalesInvoice,
    StockMovement,
    TaxRate,
    User,
};
use App\Services\Accounting\{AccountingReconciliationService, Phase7ReconciliationService};
use App\Services\Inventory\InventoryReconciliationService;
use App\Services\Money\MoneyReconciliationService;
use App\Services\Purchasing\PayablesReconciliationService;
use App\Services\Sales\SalesReconciliationService;
use App\Support\Tenancy\{CompanyContext, CompanyScope};
use Brick\Math\{BigDecimal, RoundingMode};

$handoff = json_decode(file_get_contents(getenv('PAIRED_PRIVATE_DIR').'/handoff.json'), true, flags: JSON_THROW_ON_ERROR);

$assertExactDecimal = static function (string $expected6dp, ?string $actual, string $label): void {
    if ($actual === null) {
        throw new RuntimeException("Expected {$expected6dp} for {$label}, got null.");
    }
    $actualScaled = (string) BigDecimal::of($actual)->toScale(6, RoundingMode::UNNECESSARY);
    if ($actualScaled !== $expected6dp || !BigDecimal::of($actual)->isEqualTo($expected6dp)) {
        throw new RuntimeException("Expected {$expected6dp} for {$label}, got {$actual}.");
    }
};

$computeExpectedLine = static function (string $qty, string $unitPrice, string $taxMode, ?string $taxRate, int $minorUnits): array {
    $subtotal = BigDecimal::of($qty)->multipliedBy($unitPrice)->toScale($minorUnits, RoundingMode::HALF_UP);
    $discount = BigDecimal::zero()->toScale($minorUnits, RoundingMode::UNNECESSARY);
    $netAfterDiscount = $subtotal->minus($discount);
    if ($taxMode === 'notax') {
        $tax = BigDecimal::zero()->toScale($minorUnits, RoundingMode::UNNECESSARY);
        $total = $netAfterDiscount;
    } else {
        $factor = BigDecimal::of((string) $taxRate)->dividedBy('100', 8, RoundingMode::HALF_UP);
        if ($taxMode === 'inclusive') {
            $actualNet = $netAfterDiscount->dividedBy(BigDecimal::one()->plus($factor), $minorUnits, RoundingMode::HALF_UP);
            $tax = $netAfterDiscount->minus($actualNet);
            $total = $actualNet->plus($tax);
        } elseif ($taxMode === 'exclusive') {
            $tax = $netAfterDiscount->multipliedBy($factor)->toScale($minorUnits, RoundingMode::HALF_UP);
            $total = $netAfterDiscount->plus($tax);
        } else {
            throw new RuntimeException("Unsupported tax mode [{$taxMode}].");
        }
    }

    return [
        'subtotal' => (string) $subtotal->toScale(6, RoundingMode::UNNECESSARY),
        'discount' => (string) $discount->toScale(6, RoundingMode::UNNECESSARY),
        'tax' => (string) $tax->toScale(6, RoundingMode::UNNECESSARY),
        'grand_total' => (string) $total->toScale(6, RoundingMode::UNNECESSARY),
        'payment' => (string) $total->toScale(6, RoundingMode::UNNECESSARY),
    ];
};

// Independently derived from fixture quantities and line specifications (zero test self-comparison).
$fixtureSpecs = [
    'ils_exclusive' => [
        'currency' => 'ILS',
        'minor_units' => 2,
        'expected_sales' => 5,
        'main_opening_stock' => '100.000000',
        'main_remaining_stock' => '92.750000',
        'low_opening_stock' => '1.000000',
        'low_remaining_stock' => '1.000000',
        'vectors' => [
            [
                'name' => 'ils_online_exclusive_1_5',
                'representation' => 'v2_online_sale_intent',
                'quantity' => '1.500000',
                'unit_price' => '10.000000',
                'tax_mode' => 'exclusive',
                'tax_rate' => '16.000000',
                'tax_inclusive' => false,
                'subtotal' => '15.000000',
                'discount' => '0.000000',
                'tax' => '2.400000',
                'grand_total' => '17.400000',
                'payment' => '17.400000',
            ],
            [
                'name' => 'ils_online_inclusive_1_25',
                'representation' => 'v2_online_sale_intent',
                'quantity' => '1.250000',
                'unit_price' => '10.000000',
                'tax_mode' => 'inclusive',
                'tax_rate' => '16.000000',
                'tax_inclusive' => true,
                'subtotal' => '12.500000',
                'discount' => '0.000000',
                'tax' => '1.720000',
                'grand_total' => '12.500000',
                'payment' => '12.500000',
            ],
            [
                'name' => 'ils_online_notax_0_75',
                'representation' => 'v2_online_sale_intent',
                'quantity' => '0.750000',
                'unit_price' => '10.000000',
                'tax_mode' => 'notax',
                'tax_rate' => null,
                'tax_inclusive' => false,
                'subtotal' => '7.500000',
                'discount' => '0.000000',
                'tax' => '0.000000',
                'grand_total' => '7.500000',
                'payment' => '7.500000',
            ],
            [
                'name' => 'ils_offline_exclusive_1_5',
                'representation' => 'offline_sale_intake',
                'quantity' => '1.500000',
                'unit_price' => '10.000000',
                'tax_mode' => 'exclusive',
                'tax_rate' => '16.000000',
                'tax_inclusive' => false,
                'subtotal' => '15.000000',
                'discount' => '0.000000',
                'tax' => '2.400000',
                'grand_total' => '17.400000',
                'payment' => '17.400000',
            ],
            [
                'name' => 'ils_offline_exclusive_2_25',
                'representation' => 'offline_sale_intake',
                'quantity' => '2.250000',
                'unit_price' => '10.000000',
                'tax_mode' => 'exclusive',
                'tax_rate' => '16.000000',
                'tax_inclusive' => false,
                'subtotal' => '22.500000',
                'discount' => '0.000000',
                'tax' => '3.600000',
                'grand_total' => '26.100000',
                'payment' => '26.100000',
            ],
        ],
    ],
    'jod_inclusive' => [
        'currency' => 'JOD',
        'minor_units' => 3,
        'expected_sales' => 5,
        'main_opening_stock' => '100.000000',
        'main_remaining_stock' => '93.625000',
        'low_opening_stock' => null,
        'low_remaining_stock' => null,
        'vectors' => [
            [
                'name' => 'jod_online_inclusive_1_375',
                'representation' => 'v2_online_sale_intent',
                'quantity' => '1.375000',
                'unit_price' => '10.000000',
                'tax_mode' => 'inclusive',
                'tax_rate' => '16.000000',
                'tax_inclusive' => true,
                'subtotal' => '13.750000',
                'discount' => '0.000000',
                'tax' => '1.897000',
                'grand_total' => '13.750000',
                'payment' => '13.750000',
            ],
            [
                'name' => 'jod_online_exclusive_1_125',
                'representation' => 'v2_online_sale_intent',
                'quantity' => '1.125000',
                'unit_price' => '10.000000',
                'tax_mode' => 'exclusive',
                'tax_rate' => '16.000000',
                'tax_inclusive' => false,
                'subtotal' => '11.250000',
                'discount' => '0.000000',
                'tax' => '1.800000',
                'grand_total' => '13.050000',
                'payment' => '13.050000',
            ],
            [
                'name' => 'jod_online_notax_0_375',
                'representation' => 'v2_online_sale_intent',
                'quantity' => '0.375000',
                'unit_price' => '10.000000',
                'tax_mode' => 'notax',
                'tax_rate' => null,
                'tax_inclusive' => false,
                'subtotal' => '3.750000',
                'discount' => '0.000000',
                'tax' => '0.000000',
                'grand_total' => '3.750000',
                'payment' => '3.750000',
            ],
            [
                'name' => 'jod_offline_inclusive_1_375',
                'representation' => 'offline_sale_intake',
                'quantity' => '1.375000',
                'unit_price' => '10.000000',
                'tax_mode' => 'inclusive',
                'tax_rate' => '16.000000',
                'tax_inclusive' => true,
                'subtotal' => '13.750000',
                'discount' => '0.000000',
                'tax' => '1.897000',
                'grand_total' => '13.750000',
                'payment' => '13.750000',
            ],
            [
                'name' => 'jod_offline_inclusive_2_125',
                'representation' => 'offline_sale_intake',
                'quantity' => '2.125000',
                'unit_price' => '10.000000',
                'tax_mode' => 'inclusive',
                'tax_rate' => '16.000000',
                'tax_inclusive' => true,
                'subtotal' => '21.250000',
                'discount' => '0.000000',
                'tax' => '2.931000',
                'grand_total' => '21.250000',
                'payment' => '21.250000',
            ],
        ],
    ],
    'ils_notax' => [
        'currency' => 'ILS',
        'minor_units' => 2,
        'expected_sales' => 2,
        'main_opening_stock' => '100.000000',
        'main_remaining_stock' => '96.000000',
        'low_opening_stock' => null,
        'low_remaining_stock' => null,
        'vectors' => [
            [
                'name' => 'notax_offline_1_5',
                'representation' => 'offline_sale_intake',
                'quantity' => '1.500000',
                'unit_price' => '10.000000',
                'tax_mode' => 'notax',
                'tax_rate' => null,
                'tax_inclusive' => false,
                'subtotal' => '15.000000',
                'discount' => '0.000000',
                'tax' => '0.000000',
                'grand_total' => '15.000000',
                'payment' => '15.000000',
            ],
            [
                'name' => 'notax_offline_2_5',
                'representation' => 'offline_sale_intake',
                'quantity' => '2.500000',
                'unit_price' => '10.000000',
                'tax_mode' => 'notax',
                'tax_rate' => null,
                'tax_inclusive' => false,
                'subtotal' => '25.000000',
                'discount' => '0.000000',
                'tax' => '0.000000',
                'grand_total' => '25.000000',
                'payment' => '25.000000',
            ],
        ],
    ],
];

// Self-check internal fixture derivation consistency before inspecting database state.
foreach ($fixtureSpecs as $specKey => $spec) {
    if (count($spec['vectors']) !== $spec['expected_sales']) {
        throw new RuntimeException("Fixture spec [{$specKey}] vector count mismatch.");
    }
    $soldQty = BigDecimal::zero();
    foreach ($spec['vectors'] as $vector) {
        $soldQty = $soldQty->plus($vector['quantity']);
        $derived = $computeExpectedLine($vector['quantity'], $vector['unit_price'], $vector['tax_mode'], $vector['tax_rate'], $spec['minor_units']);
        foreach ($derived as $field => $val) {
            if ($val !== $vector[$field]) {
                throw new RuntimeException("Fixture spec [{$specKey}][{$vector['name']}] {$field} derivation mismatch.");
            }
        }
    }
    $expectedRemain = (string) BigDecimal::of($spec['main_opening_stock'])->minus($soldQty)->toScale(6, RoundingMode::UNNECESSARY);
    if ($expectedRemain !== $spec['main_remaining_stock']) {
        throw new RuntimeException("Fixture spec [{$specKey}] remaining stock derivation mismatch: {$expectedRemain}.");
    }
}

$assertCompanyCountsAndRecon = static function (string $companyPublicId, int $expectedSales): Company {
    $company = Company::where('public_id', $companyPublicId)->firstOrFail();
    $owner = User::findOrFail(CompanyUser::where('company_id', $company->id)->where('is_owner', true)->value('user_id'));
    app(CompanyContext::class)->setCompany($company, $owner);
    foreach ([SalesInvoice::class, CustomerPayment::class, MobileEventReceipt::class] as $model) {
        $actual = $model::where('company_id', $company->id)->count();
        if ($actual !== $expectedSales) {
            throw new RuntimeException("Expected {$expectedSales} rows of {$model} for {$companyPublicId}, got {$actual}.");
        }
    }
    $stockCount = StockMovement::where('company_id', $company->id)->where('movement_type', StockMovement::TYPE_SALE)->count();
    if ($stockCount !== $expectedSales) {
        throw new RuntimeException("Expected {$expectedSales} sale stock legs for {$companyPublicId}, got {$stockCount}.");
    }
    foreach ([
        AccountingReconciliationService::class,
        SalesReconciliationService::class,
        MoneyReconciliationService::class,
        Phase7ReconciliationService::class,
        PayablesReconciliationService::class,
    ] as $service) {
        if (!app($service)->reconcile($company)->isHealthy) {
            throw new RuntimeException("Reconciliation failed for {$companyPublicId}: {$service}");
        }
    }
    if (!app(InventoryReconciliationService::class)->auditCompany($company)->isHealthy) {
        throw new RuntimeException("Inventory reconciliation failed for {$companyPublicId}.");
    }
    return $company;
};

$assertBalancedPostingBatch = static function (Company $company, ?int $batchId, string $sourceType, int $sourceId, string $label): void {
    if ($batchId === null) {
        throw new RuntimeException("Missing posting_batch_id for {$label}.");
    }
    $batch = PostingBatch::where('company_id', $company->id)->where('id', $batchId)->with('lines')->firstOrFail();
    if ($batch->status !== PostingBatch::STATUS_POSTED || $batch->source_type !== $sourceType || (int) $batch->source_id !== $sourceId) {
        throw new RuntimeException("Invalid posting batch metadata for {$label}.");
    }
    if ($batch->lines->isEmpty()) {
        throw new RuntimeException("Posting batch [{$batch->id}] has no lines for {$label}.");
    }
    $debits = BigDecimal::zero();
    $credits = BigDecimal::zero();
    foreach ($batch->lines as $postingLine) {
        $debits = $debits->plus((string) $postingLine->debit_base);
        $credits = $credits->plus((string) $postingLine->credit_base);
    }
    if (!$debits->isPositive() || !$debits->isEqualTo($credits)) {
        throw new RuntimeException("Unbalanced posting batch [{$batch->id}] for {$label}: dr={$debits}, cr={$credits}.");
    }
};

$assertFixtureExactStockAndSnapshots = static function (Company $company, array $fixture, array $spec) use ($assertExactDecimal, $assertBalancedPostingBatch): void {
    $key = (string) $fixture['key'];
    $setting = CompanyMobileSetting::where('company_id', $company->id)->firstOrFail();
    $warehouseId = (int) $setting->default_warehouse_id;

    $mainProduct = Product::where('company_id', $company->id)
        ->where('public_id', (string) $fixture['product'])
        ->firstOrFail();

    $mainBalanceCount = InventoryBalance::where('company_id', $company->id)
        ->where('product_id', $mainProduct->id)
        ->count();
    if ($mainBalanceCount !== 1) {
        throw new RuntimeException("Expected 1 inventory balance row for main product in {$key}, got {$mainBalanceCount}.");
    }
    $mainBalance = InventoryBalance::where('company_id', $company->id)
        ->where('warehouse_id', $warehouseId)
        ->where('product_id', $mainProduct->id)
        ->firstOrFail();
    $assertExactDecimal($spec['main_remaining_stock'], (string) $mainBalance->quantity_base, "{$key}.main_inventory_balance");

    $mainCostState = InventoryCostState::where('company_id', $company->id)
        ->where('product_id', $mainProduct->id)
        ->firstOrFail();
    $assertExactDecimal($spec['main_remaining_stock'], (string) $mainCostState->quantity_base, "{$key}.main_inventory_cost_state");

    $mainMovements = StockMovement::where('company_id', $company->id)
        ->where('product_id', $mainProduct->id)
        ->orderBy('id')
        ->get();
    $openingCount = 0;
    $saleCount = 0;
    $netMovementQty = BigDecimal::zero();
    $saleDeltaQty = BigDecimal::zero();
    foreach ($mainMovements as $mv) {
        $netMovementQty = $netMovementQty->plus((string) $mv->quantity_delta_base);
        if ($mv->movement_type === StockMovement::TYPE_OPENING_BALANCE) {
            $openingCount++;
            $assertExactDecimal($spec['main_opening_stock'], (string) $mv->quantity_delta_base, "{$key}.main_opening_movement");
        } elseif ($mv->movement_type === StockMovement::TYPE_SALE) {
            $saleCount++;
            $saleDeltaQty = $saleDeltaQty->plus((string) $mv->quantity_delta_base);
        } else {
            throw new RuntimeException("Unexpected movement type [{$mv->movement_type}] in {$key}.");
        }
    }
    if ($openingCount !== 1 || $saleCount !== $spec['expected_sales']) {
        throw new RuntimeException("Unexpected movement counts in {$key}: opening={$openingCount}, sale={$saleCount}.");
    }
    $assertExactDecimal($spec['main_remaining_stock'], (string) $netMovementQty->toScale(6, RoundingMode::UNNECESSARY), "{$key}.main_movement_sum");
    $expectedSoldDelta = (string) BigDecimal::of($spec['main_remaining_stock'])->minus($spec['main_opening_stock'])->toScale(6, RoundingMode::UNNECESSARY);
    $assertExactDecimal($expectedSoldDelta, (string) $saleDeltaQty->toScale(6, RoundingMode::UNNECESSARY), "{$key}.main_sale_delta_sum");

    if ($spec['low_remaining_stock'] !== null) {
        $lowPublicId = $fixture['low_stock_product'] ?? null;
        if (!is_string($lowPublicId) || $lowPublicId === '') {
            throw new RuntimeException("Missing low_stock_product in handoff for {$key}.");
        }
        $lowProduct = Product::where('company_id', $company->id)
            ->where('public_id', $lowPublicId)
            ->firstOrFail();
        $lowBalanceCount = InventoryBalance::where('company_id', $company->id)
            ->where('product_id', $lowProduct->id)
            ->count();
        if ($lowBalanceCount !== 1) {
            throw new RuntimeException("Expected 1 inventory balance row for low-stock product in {$key}, got {$lowBalanceCount}.");
        }
        $lowBalance = InventoryBalance::where('company_id', $company->id)
            ->where('warehouse_id', $warehouseId)
            ->where('product_id', $lowProduct->id)
            ->firstOrFail();
        $assertExactDecimal($spec['low_remaining_stock'], (string) $lowBalance->quantity_base, "{$key}.low_inventory_balance");

        $lowCostState = InventoryCostState::where('company_id', $company->id)
            ->where('product_id', $lowProduct->id)
            ->firstOrFail();
        $assertExactDecimal($spec['low_remaining_stock'], (string) $lowCostState->quantity_base, "{$key}.low_inventory_cost_state");

        $lowSaleCount = StockMovement::where('company_id', $company->id)
            ->where('product_id', $lowProduct->id)
            ->where('movement_type', StockMovement::TYPE_SALE)
            ->count();
        if ($lowSaleCount !== 0) {
            throw new RuntimeException("Expected 0 sale stock movements for low-stock product in {$key}, got {$lowSaleCount}.");
        }
        $lowMovements = StockMovement::where('company_id', $company->id)
            ->where('product_id', $lowProduct->id)
            ->get();
        if ($lowMovements->count() !== 1) {
            throw new RuntimeException("Expected 1 opening movement for low-stock product in {$key}, got {$lowMovements->count()}.");
        }
        $assertExactDecimal($spec['low_opening_stock'], (string) $lowMovements->first()->quantity_delta_base, "{$key}.low_opening_movement");
    }

    $taxExclusive = !empty($fixture['tax_exclusive'])
        ? TaxRate::where('company_id', $company->id)->where('public_id', (string) $fixture['tax_exclusive'])->firstOrFail()
        : null;
    $taxInclusive = !empty($fixture['tax_inclusive'])
        ? TaxRate::where('company_id', $company->id)->where('public_id', (string) $fixture['tax_inclusive'])->firstOrFail()
        : null;

    $receipts = MobileEventReceipt::where('company_id', $company->id)
        ->with(['salesInvoice.lines', 'customerPayment.allocations'])
        ->get();

    $matchedReceiptIds = [];
    $matchedInvoiceIds = [];
    $matchedPaymentIds = [];

    $sumSubtotal = BigDecimal::zero();
    $sumDiscount = BigDecimal::zero();
    $sumTax = BigDecimal::zero();
    $sumGrandTotal = BigDecimal::zero();
    $sumPayments = BigDecimal::zero();

    foreach ($spec['vectors'] as $vector) {
        $vLabel = "{$key}.{$vector['name']}";
        $sumSubtotal = $sumSubtotal->plus($vector['subtotal']);
        $sumDiscount = $sumDiscount->plus($vector['discount']);
        $sumTax = $sumTax->plus($vector['tax']);
        $sumGrandTotal = $sumGrandTotal->plus($vector['grand_total']);
        $sumPayments = $sumPayments->plus($vector['payment']);

        $candidates = $receipts->filter(function (MobileEventReceipt $receipt) use ($vector, $mainProduct): bool {
            if ($receipt->status !== 'completed' || $receipt->representation !== $vector['representation']) {
                return false;
            }
            $inv = $receipt->salesInvoice;
            if ($inv === null || $inv->lines->count() !== 1) {
                return false;
            }
            $line = $inv->lines->first();
            if ((int) $line->product_id !== (int) $mainProduct->id) {
                return false;
            }
            if (!BigDecimal::of((string) $line->quantity)->isEqualTo($vector['quantity'])) {
                return false;
            }
            if ((bool) $line->tax_inclusive !== $vector['tax_inclusive']) {
                return false;
            }
            if ($vector['tax_rate'] === null) {
                return $line->tax_rate_snapshot === null;
            }

            return $line->tax_rate_snapshot !== null && BigDecimal::of((string) $line->tax_rate_snapshot)->isEqualTo($vector['tax_rate']);
        })->values();

        if ($candidates->count() !== 1) {
            throw new RuntimeException("Expected 1 canonical receipt for {$vLabel}, got {$candidates->count()}.");
        }

        /** @var MobileEventReceipt $receipt */
        $receipt = $candidates->first();
        /** @var SalesInvoice $invoice */
        $invoice = $receipt->salesInvoice;
        /** @var CustomerPayment|null $payment */
        $payment = $receipt->customerPayment;
        if ($payment === null) {
            throw new RuntimeException("Missing customer payment for {$vLabel}.");
        }

        $matchedReceiptIds[] = (int) $receipt->id;
        $matchedInvoiceIds[] = (int) $invoice->id;
        $matchedPaymentIds[] = (int) $payment->id;

        // 1. Canonical MobileEventReceipt stored snapshots (submitted bytes & terminal result)
        $submitted = json_decode((string) $receipt->original_canonical_bytes, true, flags: JSON_THROW_ON_ERROR);
        if (!is_array($submitted) || ($submitted['currency'] ?? null) !== $spec['currency']
            || !is_array($submitted['lines'] ?? null) || count($submitted['lines']) !== 1) {
            throw new RuntimeException("Invalid original_canonical_bytes structure for {$vLabel}.");
        }
        $subLine = $submitted['lines'][0];
        $assertExactDecimal($vector['quantity'], (string) ($subLine['quantity'] ?? ''), "{$vLabel}.submitted.quantity");
        $assertExactDecimal($vector['unit_price'], (string) ($subLine['unit_price'] ?? ''), "{$vLabel}.submitted.unit_price");
        if ((bool) ($subLine['tax_inclusive'] ?? null) !== $vector['tax_inclusive']) {
            throw new RuntimeException("Submitted tax_inclusive mismatch for {$vLabel}.");
        }
        $assertExactDecimal($vector['tax_rate'] ?? '0.000000', (string) ($subLine['tax_rate'] ?? ''), "{$vLabel}.submitted.tax_rate");
        $submittedPay = $vector['representation'] === 'offline_sale_intake'
            ? ($submitted['payment']['amount_settled'] ?? null)
            : ($submitted['payment']['amount'] ?? null);
        $assertExactDecimal($vector['payment'], is_string($submittedPay) ? $submittedPay : null, "{$vLabel}.submitted.payment");

        $terminal = $receipt->terminal_canonical_result;
        if (!is_array($terminal) || ($terminal['status'] ?? null) !== 'posted'
            || ($terminal['invoice_id'] ?? null) !== $invoice->public_id
            || ($terminal['payment_id'] ?? null) !== $payment->public_id
            || ($terminal['currency'] ?? null) !== $spec['currency']
            || (int) ($terminal['lines_count'] ?? 0) !== 1) {
            throw new RuntimeException("Invalid terminal_canonical_result snapshot for {$vLabel}.");
        }
        $assertExactDecimal($vector['grand_total'], isset($terminal['grand_total']) ? (string) $terminal['grand_total'] : null, "{$vLabel}.terminal.grand_total");
        $assertExactDecimal($vector['payment'], isset($terminal['amount_paid']) ? (string) $terminal['amount_paid'] : null, "{$vLabel}.terminal.amount_paid");
        $assertExactDecimal('0.000000', isset($terminal['change_returned']) ? (string) $terminal['change_returned'] : null, "{$vLabel}.terminal.change_returned");

        // 2. Canonical SalesInvoice header totals & stored identity snapshots
        if ($invoice->status !== SalesInvoice::STATUS_POSTED || trim((string) $invoice->invoice_number) === '') {
            throw new RuntimeException("Invoice not canonically posted for {$vLabel}.");
        }
        if ($invoice->currency_code !== $spec['currency'] || (int) $invoice->currency_minor_units !== $spec['minor_units']
            || !BigDecimal::of((string) $invoice->exchange_rate)->isEqualTo('1')) {
            throw new RuntimeException("Invoice currency/minor-units/FX mismatch for {$vLabel}.");
        }
        if (!is_array($invoice->customer_snapshot) || ($invoice->customer_snapshot['name_ar'] ?? null) !== 'نقدي'
            || ($invoice->customer_snapshot['name_en'] ?? null) !== 'Synthetic walk in') {
            throw new RuntimeException("Invalid invoice customer_snapshot for {$vLabel}.");
        }
        if (!is_array($invoice->company_snapshot) || ($invoice->company_snapshot['name_ar'] ?? null) !== 'اختبار'
            || ($invoice->company_snapshot['name_en'] ?? null) !== 'Synthetic paired fixture '.$key) {
            throw new RuntimeException("Invalid invoice company_snapshot for {$vLabel}.");
        }
        $assertExactDecimal($vector['subtotal'], (string) $invoice->subtotal_currency, "{$vLabel}.invoice.subtotal_currency");
        $assertExactDecimal($vector['discount'], (string) $invoice->discount_total_currency, "{$vLabel}.invoice.discount_total_currency");
        $assertExactDecimal($vector['tax'], (string) $invoice->tax_total_currency, "{$vLabel}.invoice.tax_total_currency");
        $assertExactDecimal($vector['grand_total'], (string) $invoice->grand_total_currency, "{$vLabel}.invoice.grand_total_currency");
        $assertExactDecimal($vector['subtotal'], (string) $invoice->subtotal_base, "{$vLabel}.invoice.subtotal_base");
        $assertExactDecimal($vector['discount'], (string) $invoice->discount_total_base, "{$vLabel}.invoice.discount_total_base");
        $assertExactDecimal($vector['tax'], (string) $invoice->tax_total_base, "{$vLabel}.invoice.tax_total_base");
        $assertExactDecimal($vector['grand_total'], (string) $invoice->grand_total_base, "{$vLabel}.invoice.grand_total_base");
        $assertBalancedPostingBatch($company, $invoice->posting_batch_id, 'sales_invoice', (int) $invoice->id, "{$vLabel}.invoice");

        // 3. Canonical SalesInvoiceLine totals, tax snapshot, provenance snapshot & linked stock movement
        $line = $invoice->lines->first();
        $expectedTaxId = match ($vector['tax_mode']) {
            'exclusive' => $taxExclusive?->id,
            'inclusive' => $taxInclusive?->id,
            default => null,
        };
        if ((int) $line->company_id !== (int) $company->id || $line->tax_rate_id !== $expectedTaxId) {
            throw new RuntimeException("Invoice line tax_rate_id/company mismatch for {$vLabel}.");
        }
        if ($vector['tax_rate'] === null) {
            if ($line->tax_rate_snapshot !== null) {
                throw new RuntimeException("Expected null tax_rate_snapshot for {$vLabel}.");
            }
        } else {
            $assertExactDecimal($vector['tax_rate'], $line->tax_rate_snapshot, "{$vLabel}.line.tax_rate_snapshot");
        }
        if ($line->product_sku !== $mainProduct->sku || $line->product_name_ar !== 'اختبار'
            || $line->product_name_en !== 'Synthetic stock' || $line->unit_name_ar !== 'قطعة' || $line->unit_name_en !== 'Piece') {
            throw new RuntimeException("Invalid line product/unit snapshot for {$vLabel}.");
        }
        $assertExactDecimal($vector['quantity'], (string) $line->quantity, "{$vLabel}.line.quantity");
        $assertExactDecimal($vector['quantity'], (string) $line->quantity_base, "{$vLabel}.line.quantity_base");
        $assertExactDecimal('1.000000', (string) $line->unit_conversion_ratio, "{$vLabel}.line.unit_conversion_ratio");
        $assertExactDecimal($vector['unit_price'], (string) $line->unit_price, "{$vLabel}.line.unit_price");
        $assertExactDecimal($vector['subtotal'], (string) $line->line_subtotal, "{$vLabel}.line.line_subtotal");
        $assertExactDecimal($vector['discount'], (string) $line->line_discount, "{$vLabel}.line.line_discount");
        $assertExactDecimal($vector['tax'], (string) $line->line_tax, "{$vLabel}.line.line_tax");
        $assertExactDecimal($vector['grand_total'], (string) $line->line_total, "{$vLabel}.line.line_total");
        $assertExactDecimal($vector['subtotal'], (string) $line->line_subtotal_base, "{$vLabel}.line.line_subtotal_base");
        $assertExactDecimal($vector['discount'], (string) $line->line_discount_base, "{$vLabel}.line.line_discount_base");
        $assertExactDecimal($vector['tax'], (string) $line->line_tax_base, "{$vLabel}.line.line_tax_base");
        $assertExactDecimal($vector['grand_total'], (string) $line->line_total_base, "{$vLabel}.line.line_total_base");

        $movement = StockMovement::where('company_id', $company->id)->where('id', $line->stock_movement_id)->firstOrFail();
        if ((int) $movement->product_id !== (int) $mainProduct->id || $movement->movement_type !== StockMovement::TYPE_SALE
            || $movement->source_type !== 'sales_invoice' || (int) $movement->source_id !== (int) $invoice->id) {
            throw new RuntimeException("Invalid stock movement link for {$vLabel}.");
        }
        $assertExactDecimal('-'.$vector['quantity'], (string) $movement->quantity_delta_base, "{$vLabel}.stock_movement");

        // 4. Canonical CustomerPayment, allocation, and stored snapshots
        if ($payment->is_reversed || trim((string) $payment->payment_number) === ''
            || $payment->currency_code !== $spec['currency'] || !BigDecimal::of((string) $payment->exchange_rate)->isEqualTo('1')) {
            throw new RuntimeException("Invalid posted payment metadata for {$vLabel}.");
        }
        if (!is_array($payment->company_snapshot) || ($payment->company_snapshot['name_en'] ?? null) !== 'Synthetic paired fixture '.$key
            || !is_array($payment->customer_snapshot) || ($payment->customer_snapshot['name_en'] ?? null) !== 'Synthetic walk in'
            || !is_array($payment->money_account_snapshot) || ($payment->money_account_snapshot === [])) {
            throw new RuntimeException("Invalid payment stored snapshots for {$vLabel}.");
        }
        $assertExactDecimal($vector['payment'], (string) $payment->amount, "{$vLabel}.payment.amount");
        $assertExactDecimal($vector['payment'], (string) $payment->amount_base, "{$vLabel}.payment.amount_base");
        if ($payment->allocations->count() !== 1) {
            throw new RuntimeException("Expected 1 payment allocation for {$vLabel}, got {$payment->allocations->count()}.");
        }
        $allocation = $payment->allocations->first();
        if ((int) $allocation->sales_invoice_id !== (int) $invoice->id) {
            throw new RuntimeException("Payment allocation target invoice mismatch for {$vLabel}.");
        }
        $assertExactDecimal($vector['payment'], (string) $allocation->allocated_amount, "{$vLabel}.allocation.allocated_amount");
        $assertExactDecimal($vector['payment'], (string) $allocation->payment_currency_amount, "{$vLabel}.allocation.payment_currency_amount");
        $assertBalancedPostingBatch($company, $payment->posting_batch_id, 'customer_payment', (int) $payment->id, "{$vLabel}.payment");
    }

    if (count(array_unique($matchedReceiptIds)) !== $spec['expected_sales']
        || count(array_unique($matchedInvoiceIds)) !== $spec['expected_sales']
        || count(array_unique($matchedPaymentIds)) !== $spec['expected_sales']) {
        throw new RuntimeException("Vector matching did not cover all distinct receipts/invoices/payments for {$key}.");
    }

    // Verify company-wide posted sums reconcile exactly with expected vector aggregates.
    $actualSubtotalSum = BigDecimal::zero();
    $actualDiscountSum = BigDecimal::zero();
    $actualTaxSum = BigDecimal::zero();
    $actualGrandTotalSum = BigDecimal::zero();
    foreach (SalesInvoice::where('company_id', $company->id)->get() as $inv) {
        $actualSubtotalSum = $actualSubtotalSum->plus((string) $inv->subtotal_currency);
        $actualDiscountSum = $actualDiscountSum->plus((string) $inv->discount_total_currency);
        $actualTaxSum = $actualTaxSum->plus((string) $inv->tax_total_currency);
        $actualGrandTotalSum = $actualGrandTotalSum->plus((string) $inv->grand_total_currency);
    }
    $actualPaymentSum = BigDecimal::zero();
    foreach (CustomerPayment::where('company_id', $company->id)->get() as $pay) {
        $actualPaymentSum = $actualPaymentSum->plus((string) $pay->amount);
    }
    $assertExactDecimal((string) $sumSubtotal->toScale(6, RoundingMode::UNNECESSARY), (string) $actualSubtotalSum->toScale(6, RoundingMode::UNNECESSARY), "{$key}.aggregate.subtotal");
    $assertExactDecimal((string) $sumDiscount->toScale(6, RoundingMode::UNNECESSARY), (string) $actualDiscountSum->toScale(6, RoundingMode::UNNECESSARY), "{$key}.aggregate.discount");
    $assertExactDecimal((string) $sumTax->toScale(6, RoundingMode::UNNECESSARY), (string) $actualTaxSum->toScale(6, RoundingMode::UNNECESSARY), "{$key}.aggregate.tax");
    $assertExactDecimal((string) $sumGrandTotal->toScale(6, RoundingMode::UNNECESSARY), (string) $actualGrandTotalSum->toScale(6, RoundingMode::UNNECESSARY), "{$key}.aggregate.grand_total");
    $assertExactDecimal((string) $sumPayments->toScale(6, RoundingMode::UNNECESSARY), (string) $actualPaymentSum->toScale(6, RoundingMode::UNNECESSARY), "{$key}.aggregate.payment");
};

CompanyScope::executeWithoutScope(function () use ($handoff, $fixtureSpecs, $assertCompanyCountsAndRecon, $assertFixtureExactStockAndSnapshots): void {
    $seenKeys = [];
    foreach ($handoff['fixtures'] as $fixture) {
        $key = (string) ($fixture['key'] ?? '');
        if (!isset($fixtureSpecs[$key])) {
            throw new RuntimeException("Unexpected fixture key [{$key}] in handoff.");
        }
        $seenKeys[] = $key;
        $spec = $fixtureSpecs[$key];
        if ((int) $fixture['expected_sales'] !== $spec['expected_sales'] || (string) $fixture['currency'] !== $spec['currency']) {
            throw new RuntimeException("Handoff fixture metadata mismatch for [{$key}].");
        }
        $company = $assertCompanyCountsAndRecon((string) $fixture['company'], $spec['expected_sales']);
        $assertFixtureExactStockAndSnapshots($company, $fixture, $spec);
    }
    sort($seenKeys);
    $expectedKeys = array_keys($fixtureSpecs);
    sort($expectedKeys);
    if ($seenKeys !== $expectedKeys) {
        throw new RuntimeException('Handoff fixture keys do not match expected specification set.');
    }

    $foreignCompany = $assertCompanyCountsAndRecon((string) $handoff['foreign_company'], 0);
    if (StockMovement::where('company_id', $foreignCompany->id)->count() !== 0
        || InventoryBalance::where('company_id', $foreignCompany->id)->count() !== 0
        || InventoryCostState::where('company_id', $foreignCompany->id)->count() !== 0) {
        throw new RuntimeException('Foreign company must have zero stock movements or inventory state.');
    }
});
