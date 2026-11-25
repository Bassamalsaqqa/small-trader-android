<?php

declare(strict_types=1);

$path = (string) (parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: '/');
if ($path === '/paired-ready') {
    echo getenv('PAIRED_HTTP_NONCE');
    return;
}
register_shutdown_function(static function () use ($path): void {
    $method = (string) ($_SERVER['REQUEST_METHOD'] ?? 'GET');
    $status = http_response_code();
    error_log(sprintf('[paired-http] %s %s -> %s', $method, $path, $status === false ? 'unknown' : (string) $status));
});
$app = require __DIR__.'/boot.php';
$app->handleRequest(Illuminate\Http\Request::capture());
