package com.duozhi.outbound.recording.service;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.integration.config.Settings;
import com.duozhi.outbound.integration.config.UnifiedServiceSettings;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Service
public class RecordingLinks {
    private final Settings settings;

    public RecordingLinks(Config config) {
        settings = new UnifiedServiceSettings(config, "recording");
    }

    public Map<String, Object> link(Map<String, Object> asset, boolean download) {
        if (asset.get("deleted_at") != null || !"available".equals(asset.get("state")))
            throw new ApiError(409, "录音暂不可用");
        String storage = Objects.toString(asset.get("storage_uri"), "");
        if (storage.startsWith("s3://")) {
            var uri = URI.create(storage);
            String bucket = settings.get("S3_BUCKET", "ai-outbound-recordings"),
                    prefix = settings.get("S3_KEY_PREFIX", "recordings").replaceAll("^/+|/+$", "");
            String path = uri.getPath().substring(1), tenant = asset.get("tenant_id").toString();
            if (!bucket.equals(uri.getHost())
                    || !path.startsWith(prefix + "/tenant-" + tenant + "/call-")
                    || path.contains("..")
                    || uri.getQuery() != null
                    || uri.getFragment() != null) throw new ApiError(403, "录音不属于当前租户存储范围");
            String access = settings.secret("S3_ACCESS_KEY_ID"),
                    secret = settings.secret("S3_SECRET_ACCESS_KEY");
            if (access.isEmpty() || secret.isEmpty()) throw new ApiError(503, "录音访问签名尚未配置");
            try (var signer =
                    S3Presigner.builder()
                            .endpointOverride(
                                    URI.create(
                                            settings.get(
                                                    "S3_ENDPOINT_URL", "http://seaweedfs:8333")))
                            .region(Region.of(settings.get("S3_REGION", "us-east-1")))
                            .credentialsProvider(
                                    StaticCredentialsProvider.create(
                                            AwsBasicCredentials.create(access, secret)))
                            .serviceConfiguration(
                                    S3Configuration.builder().pathStyleAccessEnabled(true).build())
                            .build()) {
                var get = GetObjectRequest.builder().bucket(bucket).key(path);
                if (download)
                    get.responseContentDisposition(
                            "attachment; filename=recording-"
                                    + asset.get("id")
                                    + "."
                                    + Objects.toString(asset.get("media_format"), "wav")
                                            .replaceAll("[^a-zA-Z0-9]", ""));
                String url =
                        signer.presignGetObject(
                                        GetObjectPresignRequest.builder()
                                                .signatureDuration(Duration.ofMinutes(5))
                                                .getObjectRequest(get.build())
                                                .build())
                                .url()
                                .toString();
                return Map.of("url", url, "expires_seconds", 300);
            }
        }
        try {
            String value = Objects.toString(asset.get("provider_url"), "");
            var uri = URI.create(value);
            if (!Set.of("https", "http").contains(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || settings.production() && !"https".equals(uri.getScheme()))
                throw new IllegalArgumentException();
            return Map.of("url", value, "expires_seconds", 0, "provider_managed", true);
        } catch (Exception invalid) {
            throw new ApiError(409, "当前录音没有可播放的访问地址");
        }
    }
}
