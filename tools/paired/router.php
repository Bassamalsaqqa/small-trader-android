<?php

declare(strict_types=1);

if (parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH) === '/paired-ready') {
    echo getenv('PAIRED_HTTP_NONCE');
    return;
}
$app = require __DIR__.'/boot.php';
$app->handleRequest(Illuminate\Http\Request::capture());
