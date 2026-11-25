<?php

declare(strict_types=1);

require __DIR__.'/boot.php';
use App\Models\{Company, CompanyUser, User, SalesInvoice, CustomerPayment, StockMovement, MobileEventReceipt};
use App\Support\Tenancy\{CompanyContext, CompanyScope};
use App\Services\Accounting\{AccountingReconciliationService, Phase7ReconciliationService};
use App\Services\Sales\SalesReconciliationService;
use App\Services\Money\MoneyReconciliationService;
use App\Services\Inventory\InventoryReconciliationService;
use App\Services\Purchasing\PayablesReconciliationService;

$handoff = json_decode(file_get_contents(getenv('PAIRED_PRIVATE_DIR').'/handoff.json'), true, flags: JSON_THROW_ON_ERROR);

$assertCompanyCountsAndRecon = static function (string $companyPublicId, int $expectedSales): void {
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
};

CompanyScope::executeWithoutScope(function () use ($handoff, $assertCompanyCountsAndRecon): void {
    foreach ($handoff['fixtures'] as $fixture) {
        $assertCompanyCountsAndRecon((string) $fixture['company'], (int) $fixture['expected_sales']);
    }
    $assertCompanyCountsAndRecon((string) $handoff['foreign_company'], 0);
});
