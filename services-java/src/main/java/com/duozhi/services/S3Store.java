package com.duozhi.services;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/** Path-style S3 client using SigV4 and bounded, streamed object bodies. */
final class S3Store {
    private final Settings settings;
    private final URI endpoint;
    private final HttpClient client = Support.client();
    private final String bucket, region, access, secret;
    S3Store(Settings settings) {
        this.settings = settings; endpoint = Support.endpoint(settings.get("S3_ENDPOINT_URL", "http://seaweedfs:8333"));
        if (endpoint.getQuery() != null) throw new IllegalArgumentException("S3 endpoint must not have a query");
        bucket = settings.get("S3_BUCKET", "ai-outbound-recordings");
        if (!bucket.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,62}")) throw new IllegalArgumentException("invalid S3 bucket");
        region = settings.get("S3_REGION", "us-east-1"); access = settings.secret("S3_ACCESS_KEY_ID"); secret = settings.secret("S3_SECRET_ACCESS_KEY");
        if (access.isEmpty() || secret.isEmpty()) throw new IllegalArgumentException("S3 credentials are required");
    }
    String bucket() { return bucket; }
    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("%7E", "~"); }
    HttpResponse<InputStream> request(String method, String key, Path file, byte[] bytes, Map<String,String> extra) throws IOException, InterruptedException {
        String base = endpoint.toString().replaceAll("/+$", "");
        String path = "/" + encode(bucket);
        if (!key.isEmpty()) path += "/" + String.join("/", Arrays.stream(key.split("/", -1)).map(S3Store::encode).toList());
        URI target = URI.create(base + path);
        String hash;
        if (file == null) hash = Support.hex(Support.sha(bytes));
        else {
            try (var input = Files.newInputStream(file)) {
                MessageDigest digest;
                try { digest = MessageDigest.getInstance("SHA-256"); } catch (Exception error) { throw new IllegalStateException(error); }
                byte[] buffer = new byte[65536]; for (int n; (n = input.read(buffer)) != -1;) digest.update(buffer, 0, n);
                hash = Support.hex(digest.digest());
            }
        }
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now());
        String date = timestamp.substring(0, 8), scope = date + "/" + region + "/s3/aws4_request";
        SortedMap<String,String> headers = new TreeMap<>();
        headers.put("host", target.getRawAuthority()); headers.put("x-amz-date", timestamp); headers.put("x-amz-content-sha256", hash); headers.putAll(extra);
        String signed = String.join(";", headers.keySet());
        StringBuilder canonical = new StringBuilder(); headers.forEach((name, value) -> canonical.append(name).append(':').append(value.strip()).append('\n'));
        String request = method + "\n" + target.getRawPath() + "\n\n" + canonical + "\n" + signed + "\n" + hash;
        byte[] keyDate = Support.hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), date.getBytes(StandardCharsets.UTF_8));
        byte[] keyRegion = Support.hmac(keyDate, region.getBytes(StandardCharsets.UTF_8));
        byte[] keyService = Support.hmac(keyRegion, "s3".getBytes(StandardCharsets.UTF_8));
        byte[] signing = Support.hmac(keyService, "aws4_request".getBytes(StandardCharsets.UTF_8));
        String signature = Support.hex(Support.hmac(signing, ("AWS4-HMAC-SHA256\n" + timestamp + "\n" + scope + "\n" + Support.hex(Support.sha(request.getBytes(StandardCharsets.UTF_8)))).getBytes(StandardCharsets.UTF_8)));
        HttpRequest.Builder builder = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(settings.number("RECORDING_DOWNLOAD_TIMEOUT_SEC", 60, 1, 3600)));
        headers.forEach((name, value) -> { if (!name.equals("host")) builder.header(name, value); });
        builder.header("Authorization", "AWS4-HMAC-SHA256 Credential=" + access + "/" + scope + ", SignedHeaders=" + signed + ", Signature=" + signature);
        return client.send(builder.method(method, file == null ? HttpRequest.BodyPublishers.ofByteArray(bytes) : HttpRequest.BodyPublishers.ofFile(file)).build(), HttpResponse.BodyHandlers.ofInputStream());
    }
    boolean ready() { try { return head() == 200; } catch (Exception error) { return false; } }
    int head() throws IOException, InterruptedException { var response = request("HEAD", "", null, new byte[0], Map.of()); try (var input = response.body()) { return response.statusCode(); } }
    void initialize() throws IOException, InterruptedException {
        int status = head(); if (status == 200) return;
        if (status != 404 || !settings.flag("S3_AUTO_CREATE_BUCKET", true)) throw new Support.Failure(503, "unable to access recording bucket");
        byte[] xml = region.equals("us-east-1") ? new byte[0] : ("<CreateBucketConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><LocationConstraint>" + region.replace("&", "&amp;").replace("<", "&lt;") + "</LocationConstraint></CreateBucketConfiguration>").getBytes(StandardCharsets.UTF_8);
        var response = request("PUT", "", null, xml, Map.of()); try (var input = response.body()) { if (response.statusCode() / 100 != 2) throw new Support.Failure(503, "unable to create recording bucket"); }
    }
    void upload(String key, Path file, String type, Map<String,String> metadata) throws IOException, InterruptedException {
        Map<String,String> headers = new TreeMap<>(); headers.put("content-type", type);
        metadata.forEach((name, value) -> headers.put("x-amz-meta-" + name, value));
        var response = request("PUT", key, file, new byte[0], headers);
        try (var input = Support.timed(response.body(), 5000)) {
            if (response.statusCode() / 100 != 2) {
                String xml = new String(Support.read(input, 65536), StandardCharsets.UTF_8); var code = Pattern.compile("<Code>([A-Za-z0-9]{1,100})</Code>").matcher(xml);
                throw new Support.Failure(503, "recording upload failed (HTTP " + response.statusCode() + (code.find() ? ", " + code.group(1) : "") + ")");
            }
        }
    }
    void delete(String key) throws IOException, InterruptedException {
        var response = request("DELETE", key, null, new byte[0], Map.of());
        try (var input = response.body()) { if (response.statusCode() / 100 != 2) throw new Support.Failure(503, "recording deletion failed"); }
    }
    InputStream download(String key) throws IOException, InterruptedException {
        var response = request("GET", key, null, new byte[0], Map.of());
        if (response.statusCode() != 200) { response.body().close(); throw new Support.Failure(503, "recording verification failed"); }
        return Support.timed(response.body(), settings.number("RECORDING_DOWNLOAD_TIMEOUT_SEC", 60, 1, 3600) * 1000);
    }
}
