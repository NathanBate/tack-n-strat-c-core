<?php
declare(strict_types=1);

function loadEnv(string $primaryPath): array {
    return mergeEnvFiles(envSearchPaths($primaryPath));
}

function envSearchPaths(string $primaryPath): array {
    // Search the primary path first, then walk up the directory tree.
    // Closer files win on key conflicts. Bounded to keep the search predictable
    // on atomic-deploy layouts (release/<id>/tack-and-strat/music-api/.env →
    // walks up through the release dir to a site-root .env managed by Forge).
    $paths = [$primaryPath];
    $dir = dirname($primaryPath);
    for ($i = 0; $i < 6; $i++) {
        $parent = dirname($dir);
        if ($parent === $dir) break;
        $paths[] = $parent . '/.env';
        $dir = $parent;
    }
    return $paths;
}

function mergeEnvFiles(array $paths): array {
    $env = [];
    foreach ($paths as $path) {
        if (!is_file($path)) continue;
        $lines = @file($path, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
        if ($lines === false) continue;
        foreach ($lines as $line) {
            $line = trim($line);
            if ($line === '' || str_starts_with($line, '#')) continue;
            $parts = explode('=', $line, 2);
            if (count($parts) !== 2) continue;
            $key = trim($parts[0]);
            if (!array_key_exists($key, $env)) {
                $env[$key] = trim($parts[1]);
            }
        }
    }
    return $env;
}

function envv(array $env, string $key, ?string $default = null): ?string {
    if (array_key_exists($key, $env)) return $env[$key];
    $v = getenv($key);
    return $v !== false ? (string) $v : $default;
}

function requireSecret(array $env): void {
    $expected = (string) envv($env, 'MUSIC_API_SHARED_SECRET', '');
    $actual = $_SERVER['HTTP_X_TACKSTRAT_SECRET'] ?? '';
    if ($expected === '' || !hash_equals($expected, $actual)) {
        http_response_code(401);
        header('Content-Type: application/json');
        echo json_encode(['ok' => false, 'error' => 'Unauthorized']);
        exit;
    }
}

function musicAssetsDir(array $env): string {
    $dir = (string) envv($env, 'MUSIC_ASSETS_DIR', __DIR__ . '/music-assets');
    if (!is_dir($dir)) @mkdir($dir, 0775, true);
    return $dir;
}

/**
 * Returns the SHA-256 of the given file as lowercase hex. Caches the result
 * in `<path>.sha256` so subsequent reads are cheap. Returns null if the file
 * cannot be hashed.
 */
function fileSha256Cached(string $path): ?string {
    if (!is_file($path)) return null;
    $sidecar = $path . '.sha256';
    if (is_file($sidecar) && @filemtime($sidecar) >= @filemtime($path)) {
        $cached = @file_get_contents($sidecar);
        if (is_string($cached)) {
            $cached = trim($cached);
            if (preg_match('/^[0-9a-f]{64}$/i', $cached)) {
                return strtolower($cached);
            }
        }
    }
    $hash = @hash_file('sha256', $path);
    if (!is_string($hash)) return null;
    writeHashSidecar($path, $hash);
    return $hash;
}

/** Writes `<path>.sha256` atomically. Best-effort; silent on permission errors. */
function writeHashSidecar(string $path, string $hash): void {
    if (!preg_match('/^[0-9a-f]{64}$/i', $hash)) return;
    $sidecar = $path . '.sha256';
    $tmp = $sidecar . '.tmp';
    if (@file_put_contents($tmp, strtolower($hash) . "\n") === false) return;
    @rename($tmp, $sidecar);
}
