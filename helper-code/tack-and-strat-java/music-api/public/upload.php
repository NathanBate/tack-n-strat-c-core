<?php
declare(strict_types=1);

require dirname(__DIR__) . '/bootstrap.php';
$env = loadEnv(dirname(__DIR__) . '/.env');
requireSecret($env);

header('Content-Type: application/json');

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    http_response_code(405);
    echo json_encode(['ok' => false, 'error' => 'Method not allowed']);
    exit;
}

if (!isset($_FILES['bundle']) || !is_uploaded_file($_FILES['bundle']['tmp_name'])) {
    http_response_code(400);
    echo json_encode(['ok' => false, 'error' => 'Missing bundle upload']);
    exit;
}

$name = basename((string) $_FILES['bundle']['name']);
if (!preg_match('/\.zip$/i', $name)) {
    http_response_code(400);
    echo json_encode(['ok' => false, 'error' => 'Only .zip accepted']);
    exit;
}

$safe = preg_replace('/[^a-zA-Z0-9._-]+/', '_', $name);
$dest = rtrim(musicAssetsDir($env), '/\\') . DIRECTORY_SEPARATOR . $safe;

if (!move_uploaded_file($_FILES['bundle']['tmp_name'], $dest)) {
    http_response_code(500);
    echo json_encode(['ok' => false, 'error' => 'Could not save upload']);
    exit;
}

$hash = @hash_file('sha256', $dest);
if (is_string($hash)) {
    writeHashSidecar($dest, $hash);
}

echo json_encode([
    'ok' => true,
    'saved' => basename($dest),
    'sha256' => is_string($hash) ? $hash : null,
]);
