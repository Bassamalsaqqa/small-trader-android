<?php

declare(strict_types=1);

// Test support only. No framework boot or socket without explicit slot assignment.
$root = realpath((string) getenv('PAIRED_BACKEND_ROOT'));
$sha = '220f5c78fcedcbd2b045d0e9100317a11da07504';
if (!$root || getenv('PAIRED_OWNED_SLOT') !== '3342' || getenv('PAIRED_EXECUTE') !== 'synthetic-only') {
    throw new RuntimeException('Assign owned slot 3342 and explicit synthetic-only execution first.');
}
$head = trim((string) shell_exec('git -C '.escapeshellarg($root).' rev-parse HEAD'));
if ($head !== $sha || trim((string) shell_exec('git -C '.escapeshellarg($root).' status --porcelain --untracked-files=no')) !== '') {
    throw new RuntimeException('Backend source pin or tracked cleanliness mismatch.');
}
require $root.'/vendor/autoload.php';
use Tests\Support\DisposableMariaDbSchema;
use Symfony\Component\Process\Process;

umask(0077);
$dir = sys_get_temp_dir().'/paired-'.bin2hex(random_bytes(12));
mkdir($dir, 0700);
$schema = null;
$server = null;
$succeeded = false;
try {
    putenv('APP_ENV=testing');
    $dbSettings = DisposableMariaDbSchema::testSettings();
    if (!in_array($dbSettings['HOST'], ['127.0.0.1', 'localhost'], true) || $dbSettings['PORT'] !== '3342') {
        throw new RuntimeException('Paired slot requires explicit local MariaDB host and port 3342.');
    }
    $probe = @stream_socket_server('tcp://127.0.0.1:18400', $errno, $error);
    if (!$probe) { throw new RuntimeException('Loopback port already owned; refusing reuse.'); }
    fclose($probe);
    $schema = DisposableMariaDbSchema::create();
    $schema->migrate();
    $env = $schema->environment() + [
        'APP_KEY' => 'base64:'.base64_encode(random_bytes(32)),
        // Explicit synthetic-only process-local flags; checkout .env is never loaded and production defaults stay off.
        'MOBILE_API_ENABLED' => 'true', 'MOBILE_MERCHANT_API_ENABLED' => 'true',
        'MOBILE_V2_API_ENABLED' => 'true', 'MOBILE_V2_CASH_SLICE_ENABLED' => 'true',
        'MOBILE_V2_OFFLINE_SALES_ENABLED' => 'true',
        'PAIRED_HTTP_NONCE' => bin2hex(random_bytes(24)), 'PAIRED_PRIVATE_DIR' => $dir, 'PAIRED_BACKEND_ROOT' => $root,
    ];
    file_put_contents($dir.'/environment.json', json_encode($env, JSON_THROW_ON_ERROR));
    $fixture = new Process([PHP_BINARY, __DIR__.'/fixture.php'], $root, $env);
    $fixture->setTimeout(120)->mustRun();
    $router = __DIR__.'/router.php';
    $server = new Process([PHP_BINARY, '-S', '127.0.0.1:18400', $router], $root, $env);
    $server->disableOutput()->start();
    $ready = false;
    for ($i = 0; $i < 50; $i++) {
        if (!$server->isRunning()) { throw new RuntimeException('Owned HTTP listener failed.'); }
        $socket = @fsockopen('127.0.0.1', 18400, $errno, $error, 0.1);
        if ($socket) {
            fclose($socket);
            $body = @file_get_contents('http://127.0.0.1:18400/paired-ready');
            if (is_string($body) && hash_equals($env['PAIRED_HTTP_NONCE'], $body)) { $ready = true; break; }
        }
        usleep(100000);
    }
    if (!$ready) { throw new RuntimeException('Owned listener readiness timeout.'); }
    $android = dirname(__DIR__, 2);
    $gradleEnv = array_filter([
        'SMALL_TRADER_FINANCIAL_PILOT_HANDOFF' => $dir.'/handoff.json',
        'JAVA_HOME' => getenv('JAVA_HOME') ?: null,
        'ANDROID_HOME' => getenv('ANDROID_HOME') ?: null,
    ], static fn ($value): bool => is_string($value) && $value !== '');
    $test = new Process([
        $android.'/gradlew', '--offline', ':app:testDebugUnitTest',
        '--tests', 'com.example.data.PairedProductionSaleTest',
    ], $android, $gradleEnv);
    // Keep private test diagnostics private: never stream HTTP payloads, credentials or intents.
    $test->setTimeout(600)->run();
    file_put_contents($dir.'/gradle.log', $test->getOutput().$test->getErrorOutput());
    if (!$test->isSuccessful()) { throw new RuntimeException('Paired test failed; private diagnostics retained.'); }
    $verify = new Process([PHP_BINARY, __DIR__.'/verify.php'], $root, $env);
    $verify->setTimeout(120)->mustRun();
    $succeeded = true;
    fwrite(STDOUT, "Paired repository smoke and six reconciliations passed.\n");
} finally {
    if ($server !== null) { $server->stop(2); }
    if ($schema !== null) { $schema->drop(); }
    $cleanup = getenv('PAIRED_CLEANUP_PRIVATE');
    if ($cleanup === 'always' || ($succeeded && $cleanup === '1')) {
        foreach (glob($dir.'/*') ?: [] as $artifact) {
            if (is_file($artifact) && !is_link($artifact)) { @unlink($artifact); }
        }
        @rmdir($dir);
        fwrite(STDOUT, "Private owned artifacts cleaned.\n");
    } else {
        // Credentials and private logs retained for the owner; no secret contents printed.
        fwrite(STDOUT, 'Private owned artifacts: '.$dir.PHP_EOL);
    }
}
