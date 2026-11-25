<?php

declare(strict_types=1);

$root = (string) getenv('PAIRED_BACKEND_ROOT');
require $root.'/vendor/autoload.php';
$app = require $root.'/bootstrap/app.php';
$app->useEnvironmentPath((string) getenv('PAIRED_PRIVATE_DIR'));
$app->make(Illuminate\Contracts\Console\Kernel::class)->bootstrap();
// Reuse the current tracked nonce guard; do not replace with a schema-name check.
(new class {
    use Tests\Support\EnforcesDisposableDatabaseOwnership;
    public function verify(): void { $this->assertOwnedDisposableDatabase(); }
})->verify();
return $app;
