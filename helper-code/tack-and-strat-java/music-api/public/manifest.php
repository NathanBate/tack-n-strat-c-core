<?php
declare(strict_types=1);

require dirname(__DIR__) . '/bootstrap.php';
$env = loadEnv(dirname(__DIR__) . '/.env');
requireSecret($env);

header('Content-Type: application/json');

$dir = musicAssetsDir($env);
$files = @scandir($dir) ?: [];
$tracks = [];
foreach ($files as $f) {
    if ($f === '.' || $f === '..') continue;
    if (!preg_match('/\.zip$/i', $f)) continue;
    $full = $dir . DIRECTORY_SEPARATOR . $f;
    $tracks[] = [
        'id' => pathinfo($f, PATHINFO_FILENAME),
        'filename' => $f,
        'bytes' => @filesize($full) ?: 0,
        'sha256' => fileSha256Cached($full),
    ];
}

echo json_encode([
    'ok' => true,
    'count' => count($tracks),
    'tracks' => $tracks,
], JSON_UNESCAPED_SLASHES);
