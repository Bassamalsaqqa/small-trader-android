<?php

declare(strict_types=1);

require __DIR__.'/boot.php';
use App\Actions\Company\CreateCompanyAction;
use App\Actions\Inventory\PostOpeningStockAction;
use App\Actions\Sales\SaveTaxRateAction;
use App\Domain\Inventory\ValueObjects\Quantity;
use App\Models\{User, Company, CompanyUser, CompanyMobileSetting, Customer, Warehouse, Unit, Product, ProductUnit, MoneyAccount, LedgerAccount};
use App\Services\Tenancy\CompanyRoleService;
use App\Support\Tenancy\CompanyContext;
use Illuminate\Support\Facades\Hash;
use Illuminate\Support\Str;

$createStaff = static function (string $prefix): array {
    $password = bin2hex(random_bytes(24));
    $user = User::factory()->create([
        'email' => $prefix.'-'.bin2hex(random_bytes(8)).'@example.test',
        'password' => Hash::make($password),
    ]);
    return ['user' => $user, 'password' => $password];
};

$staffCreds = $createStaff('paired-staff');
$otherStaffCreds = $createStaff('paired-other');
$deviceStaffCreds = $createStaff('paired-device');
$staff = $staffCreds['user'];
$otherStaff = $otherStaffCreds['user'];
$deviceStaff = $deviceStaffCreds['user'];
$owner = User::factory()->create();
$foreignOwner = User::factory()->create();

$assignSalesMember = static function (Company $company, User $member): void {
    CompanyUser::create(['company_id' => $company->id, 'user_id' => $member->id, 'status' => 'active', 'is_owner' => false]);
    setPermissionsTeamId($company->id);
    $member->unsetRelation('roles')->unsetRelation('permissions')->assignRole('Sales');
    $member->givePermissionTo([
        'sales.invoice.create',
        'sales.invoice.post',
        'sales.invoice.view',
        'money.receipt.create',
        'money.receipt.allocate',
        'money.receipt.view',
    ]);
};

$specs = [
    [
        'key' => 'ils_exclusive',
        'currency' => 'ILS',
        'offline_tax_mode' => 'exclusive',
        'with_taxes' => true,
        'with_low_stock' => true,
        'with_extra_members' => true,
        'expected_sales' => 5,
    ],
    [
        'key' => 'jod_inclusive',
        'currency' => 'JOD',
        'offline_tax_mode' => 'inclusive',
        'with_taxes' => true,
        'with_low_stock' => false,
        'with_extra_members' => false,
        'expected_sales' => 5,
    ],
    [
        'key' => 'ils_notax',
        'currency' => 'ILS',
        'offline_tax_mode' => 'notax',
        'with_taxes' => false,
        'with_low_stock' => false,
        'with_extra_members' => false,
        'expected_sales' => 2,
    ],
];

$fixtures = [];
foreach ($specs as $spec) {
    $currency = $spec['currency'];
    $company = app(CreateCompanyAction::class)->execute($owner, [
        'name_ar' => 'اختبار',
        'name_en' => 'Synthetic paired fixture '.$spec['key'],
        'base_currency_code' => $currency,
    ]);
    auth()->setUser($owner);
    app(CompanyContext::class)->setCompany($company, $owner);
    app(CompanyRoleService::class)->ensurePermissionsExist();
    app(CompanyRoleService::class)->seedCompanyRoles($company);
    setPermissionsTeamId($company->id);
    $owner->unsetRelation('roles')->assignRole('Owner');

    $assignSalesMember($company, $staff);
    if ($spec['with_extra_members']) {
        $assignSalesMember($company, $otherStaff);
        $assignSalesMember($company, $deviceStaff);
    }

    $customer = Customer::create([
        'company_id' => $company->id,
        'name_ar' => 'نقدي',
        'name_en' => 'Synthetic walk in',
        'default_currency_code' => $currency,
        'preferred_locale' => 'en',
        'status' => 'active',
        'created_by' => $owner->id,
    ]);
    $warehouse = Warehouse::where('company_id', $company->id)->where('is_default', true)->firstOrFail();
    $unit = Unit::where('company_id', $company->id)->where('code', 'piece')->firstOrFail();
    $unit->update(['allows_fraction' => true, 'decimal_places' => 6]);

    $createStockProduct = static function (string $sku, string $nameEn, string $openingQty) use ($company, $unit, $warehouse, $owner): Product {
        $product = Product::create([
            'company_id' => $company->id,
            'base_unit_id' => $unit->id,
            'name_ar' => 'اختبار',
            'name_en' => $nameEn,
            'sku' => $sku,
            'product_type' => 'stock',
            'track_stock' => true,
            'active' => true,
            'created_by' => $owner->id,
        ]);
        ProductUnit::create([
            'company_id' => $company->id,
            'product_id' => $product->id,
            'unit_id' => $unit->id,
            'conversion_to_base' => '1.000000',
            'is_base' => true,
            'is_default_sale' => true,
            'is_default_purchase' => true,
            'active' => true,
            'default_sale_price_base' => '10.000000',
        ]);
        app(PostOpeningStockAction::class)->execute(
            company: $company,
            product: $product,
            warehouse: $warehouse,
            quantity: Quantity::of($openingQty),
            unitCostBase: '2.000000',
            user: $owner,
            idempotencyKey: 'paired-'.$sku,
        );
        return $product;
    };

    $product = $createStockProduct('PAIRED-'.strtoupper($spec['key']), 'Synthetic stock', '100.000000');
    $lowStockProduct = $spec['with_low_stock']
        ? $createStockProduct('PAIRED-LOW-'.strtoupper($spec['key']), 'Synthetic limited stock', '1.000000')
        : null;

    $taxExclusive = null;
    $taxInclusive = null;
    if ($spec['with_taxes']) {
        $taxOutput = LedgerAccount::where('company_id', $company->id)->where('system_key', 'tax_output')->firstOrFail();
        $taxExclusive = app(SaveTaxRateAction::class)->execute($company, $owner, [
            'code' => 'VAT16EX',
            'name_ar' => 'ضريبة 16% مضافة',
            'name_en' => 'VAT 16% Exclusive',
            'calculation' => 'exclusive',
            'rate' => '16',
            'sales_tax_account_id' => $taxOutput->id,
            'purchase_tax_account_id' => null,
            'active' => true,
        ]);
        $taxInclusive = app(SaveTaxRateAction::class)->execute($company, $owner, [
            'code' => 'VAT16IN',
            'name_ar' => 'ضريبة 16% شاملة',
            'name_en' => 'VAT 16% Inclusive',
            'calculation' => 'inclusive',
            'rate' => '16',
            'sales_tax_account_id' => $taxOutput->id,
            'purchase_tax_account_id' => null,
            'active' => true,
        ]);
    }

    $defaultTaxRateId = match ($spec['offline_tax_mode']) {
        'exclusive' => $taxExclusive?->id,
        'inclusive' => $taxInclusive?->id,
        default => null,
    };

    $cash = MoneyAccount::where('company_id', $company->id)
        ->where('account_type', MoneyAccount::TYPE_CASH)
        ->where('currency_code', $currency)
        ->firstOrFail();

    // Explicit synthetic-only limits; these are not production recommendations.
    CompanyMobileSetting::updateOrCreate(['company_id' => $company->id], [
        'walk_in_customer_id' => $customer->id,
        'default_warehouse_id' => $warehouse->id,
        'approved_cash_account_id' => $cash->id,
        'default_tax_rate_id' => $defaultTaxRateId,
        'offline_policy_enabled' => true,
        'offline_cash_only' => true,
        'max_offline_hours' => 1,
        'max_offline_sales_count' => 10,
        'max_offline_total_amount' => '1000.000000',
    ]);

    $fixtures[] = [
        'key' => $spec['key'],
        'company' => (string) $company->public_id,
        'currency' => $currency,
        'offline_tax_mode' => $spec['offline_tax_mode'],
        'product' => (string) $product->public_id,
        'low_stock_product' => $lowStockProduct ? (string) $lowStockProduct->public_id : null,
        'tax_exclusive' => $taxExclusive ? (string) $taxExclusive->public_id : null,
        'tax_inclusive' => $taxInclusive ? (string) $taxInclusive->public_id : null,
        'expected_sales' => $spec['expected_sales'],
    ];

    auth()->forgetUser();
    app(CompanyContext::class)->clearSessionless();
}

$foreignCompany = app(CreateCompanyAction::class)->execute($foreignOwner, [
    'name_ar' => 'شركة أجنبية',
    'name_en' => 'Synthetic foreign tenant',
    'base_currency_code' => 'ILS',
]);
auth()->setUser($foreignOwner);
app(CompanyContext::class)->setCompany($foreignCompany, $foreignOwner);
$foreignCustomer = Customer::create([
    'company_id' => $foreignCompany->id,
    'name_ar' => 'نقدي',
    'name_en' => 'Foreign walk in',
    'default_currency_code' => 'ILS',
    'preferred_locale' => 'en',
    'status' => 'active',
    'created_by' => $foreignOwner->id,
]);
$foreignWarehouse = Warehouse::where('company_id', $foreignCompany->id)->where('is_default', true)->firstOrFail();
$foreignCash = MoneyAccount::where('company_id', $foreignCompany->id)
    ->where('account_type', MoneyAccount::TYPE_CASH)
    ->where('currency_code', 'ILS')
    ->firstOrFail();
CompanyMobileSetting::updateOrCreate(['company_id' => $foreignCompany->id], [
    'walk_in_customer_id' => $foreignCustomer->id,
    'default_warehouse_id' => $foreignWarehouse->id,
    'approved_cash_account_id' => $foreignCash->id,
    'default_tax_rate_id' => null,
    'offline_policy_enabled' => true,
    'offline_cash_only' => true,
    'max_offline_hours' => 1,
    'max_offline_sales_count' => 10,
    'max_offline_total_amount' => '1000.000000',
]);
auth()->forgetUser();
app(CompanyContext::class)->clearSessionless();

$dir = (string) getenv('PAIRED_PRIVATE_DIR');
file_put_contents($dir.'/credentials.json', json_encode([
    'email' => $staff->email,
    'password' => $staffCreds['password'],
    'other_email' => $otherStaff->email,
    'other_password' => $otherStaffCreds['password'],
    'device_email' => $deviceStaff->email,
    'device_password' => $deviceStaffCreds['password'],
], JSON_THROW_ON_ERROR));

file_put_contents($dir.'/handoff.json', json_encode([
    'backend' => '220f5c78fcedcbd2b045d0e9100317a11da07504',
    'origin' => 'http://127.0.0.1:18400',
    'credentials' => $dir.'/credentials.json',
    'device' => (string) Str::uuid(),
    'second_device' => (string) Str::uuid(),
    'other_device' => (string) Str::uuid(),
    'revoked_device' => (string) Str::uuid(),
    'expires' => time() + 900,
    'foreign_company' => (string) $foreignCompany->public_id,
    'fixtures' => $fixtures,
], JSON_THROW_ON_ERROR));
