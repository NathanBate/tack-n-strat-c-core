package com.tackstrat.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

final class MusicSyncClient {

    private static final Path BUNDLES_DIR = Path.of("assets/music/envato-bundles");
    private static final Path BUNDLES_DIR_ALT = Path.of("tack-and-strat/assets/music/envato-bundles");
    private static final ObjectMapper JSON = new ObjectMapper();

    record SyncResult(int attempted, int uploaded, List<String> errors) {}

    record TestResult(boolean ok, int statusCode, int trackCount, String message) {}

    record DownloadResult(int remoteCount, int downloaded, int skipped, List<String> errors) {}

    record DownloadProgress(int completed, int total, String currentFilename) {}

    enum SyncPhase { UPLOAD, DOWNLOAD }

    record FullProgress(SyncPhase phase, int stepIndex, int stepTotal, String filename, String detail) {}

    record FullSyncResult(
            int uploaded,
            int uploadSkipped,
            int uploadAttempted,
            int downloaded,
            int downloadSkipped,
            int downloadRemoteCount,
            List<String> errors) {}

    TestResult testConnection(String baseUrl, String sharedSecret) {
        String api = baseUrl == null ? "" : baseUrl.trim();
        String secret = sharedSecret == null ? "" : sharedSecret.trim();
        if (api.isBlank()) {
            return new TestResult(false, 0, 0, "Music API URL is blank.");
        }
        if (secret.isBlank()) {
            return new TestResult(false, 0, 0, "Shared secret is blank.");
        }
        String endpoint = api.endsWith("/") ? api + "manifest.php" : api + "/manifest.php";
        try {
            var http = HttpClient.newHttpClient();
            var req = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("X-Tackstrat-Secret", secret)
                    .GET()
                    .build();
            var rsp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = rsp.statusCode();
            String body = rsp.body();
            if (code >= 200 && code < 300) {
                int count = parseTrackCount(body);
                if (count >= 0) {
                    return new TestResult(true, code, count, "Connected. Server reports " + count + " track(s).");
                }
                return new TestResult(false, code, 0,
                        "HTTP " + code + " but response was unparseable" + describeBody(body));
            }
            return new TestResult(false, code, 0, "HTTP " + code + describeBody(body));
        } catch (Exception ex) {
            return new TestResult(false, 0, 0, "Request failed: " + ex.getMessage());
        }
    }

    private static int parseTrackCount(String body) {
        if (body == null || body.isBlank()) return -1;
        try {
            JsonNode node = JSON.readTree(body);
            JsonNode count = node.get("count");
            if (count != null && count.isInt()) return count.asInt();
            JsonNode tracks = node.get("tracks");
            if (tracks != null && tracks.isArray()) return tracks.size();
        } catch (IOException ignored) {
        }
        return -1;
    }

    DownloadResult syncFromServer(String baseUrl, String sharedSecret, Consumer<DownloadProgress> onProgress) {
        String api = baseUrl == null ? "" : baseUrl.trim();
        String secret = sharedSecret == null ? "" : sharedSecret.trim();
        var errors = new ArrayList<String>();
        if (api.isBlank()) {
            errors.add("Music API URL is blank.");
            return new DownloadResult(0, 0, 0, errors);
        }
        if (secret.isBlank()) {
            errors.add("Shared secret is blank.");
            return new DownloadResult(0, 0, 0, errors);
        }
        Path dir;
        try {
            dir = resolveOrCreateBundlesDir();
        } catch (IOException ex) {
            errors.add("Could not create bundles dir: " + ex.getMessage());
            return new DownloadResult(0, 0, 0, errors);
        }

        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

        List<RemoteTrack> remote;
        try {
            remote = fetchManifest(http, api, secret);
        } catch (Exception ex) {
            errors.add("Manifest fetch failed: " + ex.getMessage());
            return new DownloadResult(0, 0, 0, errors);
        }

        return doDownloadFromManifest(http, api, secret, dir, remote, onProgress, errors);
    }

    FullSyncResult syncBidirectional(String baseUrl, String sharedSecret, Consumer<FullProgress> onProgress) {
        String api = baseUrl == null ? "" : baseUrl.trim();
        String secret = sharedSecret == null ? "" : sharedSecret.trim();
        var errors = new ArrayList<String>();
        if (api.isBlank()) {
            errors.add("Music API URL is blank.");
            return new FullSyncResult(0, 0, 0, 0, 0, 0, errors);
        }
        if (secret.isBlank()) {
            errors.add("Shared secret is blank.");
            return new FullSyncResult(0, 0, 0, 0, 0, 0, errors);
        }
        Path dir;
        try {
            dir = resolveOrCreateBundlesDir();
        } catch (IOException ex) {
            errors.add("Could not create bundles dir: " + ex.getMessage());
            return new FullSyncResult(0, 0, 0, 0, 0, 0, errors);
        }

        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        List<RemoteTrack> remote;
        try {
            remote = fetchManifest(http, api, secret);
        } catch (Exception ex) {
            errors.add("Manifest fetch failed: " + ex.getMessage());
            return new FullSyncResult(0, 0, 0, 0, 0, 0, errors);
        }

        SmartUploadBatch uploads = syncToServerSmart(http, api, secret, dir, remote, onProgress);
        errors.addAll(uploads.errors());

        var downloadErrors = new ArrayList<String>();
        DownloadResult down = doDownloadFromManifest(
                http, api, secret, dir, remote,
                onProgress == null ? null : p -> onProgress.accept(new FullProgress(
                        SyncPhase.DOWNLOAD, p.completed(), p.total(), p.currentFilename(),
                        "Downloading from server")),
                downloadErrors);
        errors.addAll(downloadErrors);

        return new FullSyncResult(
                uploads.uploaded(),
                uploads.skipped(),
                uploads.attempted(),
                down.downloaded(),
                down.skipped(),
                down.remoteCount(),
                errors);
    }

    private record SmartUploadBatch(int attempted, int uploaded, int skipped, List<String> errors) {}

    private SmartUploadBatch syncToServerSmart(
            HttpClient http,
            String baseUrl,
            String secret,
            Path localDir,
            List<RemoteTrack> remote,
            Consumer<FullProgress> onProgress) {
        var errors = new ArrayList<String>();
        var byName = new HashMap<String, RemoteTrack>();
        for (RemoteTrack t : remote) {
            byName.put(t.filename().toLowerCase(Locale.ROOT), t);
        }
        List<Path> localZips;
        try {
            localZips = listZips(localDir);
        } catch (IOException ex) {
            errors.add("Could not list local bundles: " + ex.getMessage());
            return new SmartUploadBatch(0, 0, 0, errors);
        }
        int toProcess = 0;
        for (Path zip : localZips) {
            String lower = zip.getFileName().toString().toLowerCase(Locale.ROOT);
            RemoteTrack onServer = byName.get(lower);
            if (onServer == null || needsUpload(zip, onServer)) toProcess++;
        }
        int uploaded = 0;
        int skipped = 0;
        int uploadOrd = 0;
        for (Path zip : localZips) {
            String lower = zip.getFileName().toString().toLowerCase(Locale.ROOT);
            RemoteTrack onServer = byName.get(lower);
            if (onServer != null && !needsUpload(zip, onServer)) {
                skipped++;
                continue;
            }
            uploadOrd++;
            if (onProgress != null) {
                String reason = onServer == null ? "new for server" : "updated";
                onProgress.accept(new FullProgress(
                        SyncPhase.UPLOAD, uploadOrd, Math.max(1, toProcess),
                        zip.getFileName().toString(), "Uploading (" + reason + ")"));
            }
            try {
                HttpUploadResponse outcome = uploadZip(http, baseUrl, secret, zip);
                if (outcome.code() >= 200 && outcome.code() < 300) {
                    uploaded++;
                } else {
                    errors.add(zip.getFileName() + " -> HTTP " + outcome.code()
                            + describeBody(outcome.body()));
                }
            } catch (Exception ex) {
                errors.add(zip.getFileName() + " -> " + ex.getMessage());
            }
        }
        return new SmartUploadBatch(localZips.size(), uploaded, skipped, errors);
    }

    private static boolean needsUpload(Path localZip, RemoteTrack onServer) {
        long size = safeSize(localZip);
        if (size <= 0) return true;
        if (onServer.bytes() > 0 && size != onServer.bytes()) return true;
        if (onServer.sha256() == null || onServer.sha256().isBlank()) return false;
        String h = sha256Hex(localZip);
        return h == null || !h.equalsIgnoreCase(onServer.sha256());
    }

    private DownloadResult doDownloadFromManifest(
            HttpClient http,
            String api,
            String secret,
            Path dir,
            List<RemoteTrack> remote,
            Consumer<DownloadProgress> onProgress,
            List<String> errors) {
        int total = remote.size();
        int downloaded = 0;
        int skipped = 0;
        if (onProgress != null) onProgress.accept(new DownloadProgress(0, total, null));
        int idx = 0;
        for (RemoteTrack t : remote) {
            if (onProgress != null) onProgress.accept(new DownloadProgress(idx, total, t.filename()));
            Path local = dir.resolve(t.filename());
            if (localMatchesRemote(local, t)) {
                skipped++;
            } else {
                try {
                    downloadOne(http, api, secret, t.filename(), local);
                    if (t.sha256() != null) {
                        String got = sha256Hex(local);
                        if (got != null && !got.equalsIgnoreCase(t.sha256())) {
                            try {
                                Files.deleteIfExists(local);
                            } catch (IOException ignored) {
                            }
                            throw new IOException("checksum mismatch (got " + got
                                    + ", expected " + t.sha256() + ")");
                        }
                    }
                    downloaded++;
                } catch (Exception ex) {
                    errors.add(t.filename() + " -> " + ex.getMessage());
                }
            }
            idx++;
            if (onProgress != null) onProgress.accept(new DownloadProgress(idx, total, t.filename()));
        }
        return new DownloadResult(total, downloaded, skipped, errors);
    }

    private static boolean localMatchesRemote(Path local, RemoteTrack t) {
        if (!Files.isRegularFile(local)) return false;
        if (t.bytes() <= 0 || safeSize(local) != t.bytes()) return false;
        // If the server didn't supply a hash (older deploy), fall back to size-only.
        if (t.sha256() == null || t.sha256().isBlank()) return true;
        String localHash = sha256Hex(local);
        return localHash != null && localHash.equalsIgnoreCase(t.sha256());
    }

    private static String sha256Hex(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest()).toLowerCase(Locale.ROOT);
        } catch (IOException | NoSuchAlgorithmException ex) {
            return null;
        }
    }

    private record RemoteTrack(String filename, long bytes, String sha256) {}

    private static List<RemoteTrack> fetchManifest(HttpClient http, String baseUrl, String secret)
            throws IOException, InterruptedException {
        String endpoint = baseUrl.endsWith("/") ? baseUrl + "manifest.php" : baseUrl + "/manifest.php";
        var req = HttpRequest.newBuilder(URI.create(endpoint))
                .header("X-Tackstrat-Secret", secret)
                .GET()
                .build();
        var rsp = http.send(req, HttpResponse.BodyHandlers.ofString());
        int code = rsp.statusCode();
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + describeBody(rsp.body()));
        }
        JsonNode node = JSON.readTree(rsp.body());
        JsonNode tracks = node.get("tracks");
        if (tracks == null || !tracks.isArray()) return List.of();
        var out = new ArrayList<RemoteTrack>(tracks.size());
        for (JsonNode t : tracks) {
            JsonNode fn = t.get("filename");
            JsonNode bytes = t.get("bytes");
            JsonNode sha = t.get("sha256");
            if (fn == null || !fn.isTextual()) continue;
            long size = bytes != null && bytes.canConvertToLong() ? bytes.asLong() : -1L;
            String hash = sha != null && sha.isTextual() ? sha.asText() : null;
            out.add(new RemoteTrack(fn.asText(), size, hash));
        }
        return out;
    }

    private static void downloadOne(HttpClient http, String baseUrl, String secret, String filename, Path target)
            throws IOException, InterruptedException {
        String endpoint = (baseUrl.endsWith("/") ? baseUrl : baseUrl + "/")
                + "download.php?file=" + URLEncoder.encode(filename, StandardCharsets.UTF_8);
        var req = HttpRequest.newBuilder(URI.create(endpoint))
                .header("X-Tackstrat-Secret", secret)
                .GET()
                .build();
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        Files.deleteIfExists(tmp);
        HttpResponse<Path> rsp = http.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
        int code = rsp.statusCode();
        if (code < 200 || code >= 300) {
            String body = "";
            try {
                body = Files.readString(tmp);
            } catch (IOException ignored) {
            }
            Files.deleteIfExists(tmp);
            throw new IOException("HTTP " + code + describeBody(body));
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static long safeSize(Path p) {
        try {
            return Files.size(p);
        } catch (IOException ex) {
            return -1L;
        }
    }

    private static Path resolveOrCreateBundlesDir() throws IOException {
        Path d = resolveBundlesDir();
        if (d != null) return d;
        Path parent = Path.of("tack-and-strat");
        Path target = Files.isDirectory(parent) ? BUNDLES_DIR_ALT : BUNDLES_DIR;
        Files.createDirectories(target);
        return target;
    }

    /** Returns the resolved local bundles directory if one exists, else null. */
    static Path findExistingBundlesDir() {
        return resolveBundlesDir();
    }

    /** Returns the count of .zip files in the local bundles directory (0 if none/missing). */
    static int countLocalBundles() {
        Path dir = resolveBundlesDir();
        if (dir == null) return 0;
        try {
            return listZips(dir).size();
        } catch (IOException ex) {
            return 0;
        }
    }

    record CacheClearResult(int removedFiles, long freedBytes, List<String> errors) {}

    /**
     * Wipes downloaded zip bundles and any extracted wav working files. Leaves
     * the directories themselves in place (so `BackgroundMusicPlayer` can
     * continue polling them). Best-effort: returns counts and any per-file
     * errors rather than throwing.
     */
    static CacheClearResult clearLocalCache() {
        var errors = new ArrayList<String>();
        long freed = 0;
        int removed = 0;
        for (Path target : new Path[] { resolveBundlesDir(), resolveExpandedDir() }) {
            if (target == null || !Files.isDirectory(target)) continue;
            try (Stream<Path> s = Files.list(target)) {
                for (Path p : s.toList()) {
                    if (!Files.isRegularFile(p)) continue;
                    long size = safeSize(p);
                    try {
                        Files.delete(p);
                        removed++;
                        if (size > 0) freed += size;
                    } catch (IOException ex) {
                        errors.add(p.getFileName() + ": " + ex.getMessage());
                    }
                }
            } catch (IOException ex) {
                errors.add(target + ": " + ex.getMessage());
            }
        }
        return new CacheClearResult(removed, freed, errors);
    }

    private static final Path EXPANDED_DIR = Path.of("assets/music/envato-expanded");
    private static final Path EXPANDED_DIR_ALT = Path.of("tack-and-strat/assets/music/envato-expanded");

    private static Path resolveExpandedDir() {
        if (Files.isDirectory(EXPANDED_DIR)) return EXPANDED_DIR;
        if (Files.isDirectory(EXPANDED_DIR_ALT)) return EXPANDED_DIR_ALT;
        return null;
    }

    SyncResult syncToServer(String baseUrl, String sharedSecret) {
        String api = baseUrl == null ? "" : baseUrl.trim();
        String secret = sharedSecret == null ? "" : sharedSecret.trim();
        var errors = new ArrayList<String>();
        if (api.isBlank()) {
            errors.add("Music API URL is blank.");
            return new SyncResult(0, 0, errors);
        }
        if (secret.isBlank()) {
            errors.add("Shared secret is blank.");
            return new SyncResult(0, 0, errors);
        }
        Path dir = resolveBundlesDir();
        if (dir == null || !Files.isDirectory(dir)) {
            errors.add("Local bundles folder not found.");
            return new SyncResult(0, 0, errors);
        }
        List<Path> zips;
        try {
            zips = listZips(dir);
        } catch (IOException ex) {
            errors.add("Could not list local bundles: " + ex.getMessage());
            return new SyncResult(0, 0, errors);
        }
        int uploaded = 0;
        var http = HttpClient.newHttpClient();
        for (Path zip : zips) {
            try {
                HttpUploadResponse outcome = uploadZip(http, api, secret, zip);
                if (outcome.code() >= 200 && outcome.code() < 300) {
                    uploaded++;
                } else {
                    errors.add(zip.getFileName() + " -> HTTP " + outcome.code()
                            + describeBody(outcome.body()));
                }
            } catch (Exception ex) {
                errors.add(zip.getFileName() + " -> " + ex.getMessage());
            }
        }
        return new SyncResult(zips.size(), uploaded, errors);
    }

    private record HttpUploadResponse(int code, String body) {}

    private static String describeBody(String body) {
        if (body == null) return "";
        String trimmed = body.strip();
        if (trimmed.isEmpty()) return "";
        if (trimmed.length() > 200) {
            trimmed = trimmed.substring(0, 200) + "…";
        }
        return " (" + trimmed + ")";
    }

    private static Path resolveBundlesDir() {
        if (Files.isDirectory(BUNDLES_DIR)) return BUNDLES_DIR;
        if (Files.isDirectory(BUNDLES_DIR_ALT)) return BUNDLES_DIR_ALT;
        return null;
    }

    private static List<Path> listZips(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".zip"))
                    .sorted()
                    .toList();
        }
    }

    private static HttpUploadResponse uploadZip(HttpClient http, String baseUrl, String secret, Path zip)
            throws IOException, InterruptedException {
        String endpoint = baseUrl.endsWith("/") ? baseUrl + "upload.php" : baseUrl + "/upload.php";
        String boundary = "----TackStratBoundary" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipartZip(boundary, zip);
        var req = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-Tackstrat-Secret", secret)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        var rsp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return new HttpUploadResponse(rsp.statusCode(), rsp.body());
    }

    private static byte[] multipartZip(String boundary, Path zip) throws IOException {
        String filename = zip.getFileName().toString();
        byte[] fileBytes = Files.readAllBytes(zip);
        var out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\n").getBytes());
        out.write(("Content-Disposition: form-data; name=\"bundle\"; filename=\"" + filename + "\"\r\n").getBytes());
        out.write(("Content-Type: application/zip\r\n\r\n").getBytes());
        out.write(fileBytes);
        out.write("\r\n".getBytes());
        out.write(("--" + boundary + "--\r\n").getBytes());
        return out.toByteArray();
    }
}
