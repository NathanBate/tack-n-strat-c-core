<?php
declare(strict_types=1);

require dirname(__DIR__) . '/bootstrap.php';
$env = loadEnv(dirname(__DIR__) . '/.env');
requireSecret($env);

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    http_response_code(405);
    header('Content-Type: application/json');
    echo json_encode(['ok' => false, 'error' => 'Method not allowed']);
    exit;
}

$file = isset($_GET['file']) ? basename((string) $_GET['file']) : '';
if ($file === '' || !preg_match('/\.zip$/i', $file)) {
    http_response_code(400);
    header('Content-Type: application/json');
    echo json_encode(['ok' => false, 'error' => 'Bad or missing file parameter']);
    exit;
}

$dir = rtrim(musicAssetsDir($env), '/\\');
$path = $dir . DIRECTORY_SEPARATOR . $file;

// Belt-and-braces: ensure the resolved path stays inside the assets dir even
// after symlink/realpath resolution. Forces basename() above to be the only
// thing that determines the served file.
$resolvedDir = realpath($dir);
$resolvedPath = realpath($path);
if ($resolvedDir === false || $resolvedPath === false
    || strncmp($resolvedPath, $resolvedDir . DIRECTORY_SEPARATOR, strlen($resolvedDir) + 1) !== 0) {
    http_response_code(404);
    header('Content-Type: application/json');
    echo json_encode(['ok' => false, 'error' => 'Not found']);
    exit;
}

if (!is_file($resolvedPath)) {
    http_response_code(404);
    header('Content-Type: application/json');
    echo json_encode(['ok' => false, 'error' => 'Not found']);
    exit;
}

$size = filesize($resolvedPath);
header('Content-Type: application/zip');
if ($size !== false) {
    header('Content-Length: ' . $size);
}
header('Content-Disposition: attachment; filename="' . $file . '"');
header('X-Content-Type-Options: nosniff');
@ob_end_clean();
readfile($resolvedPath);
