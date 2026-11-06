package com.duozhi.outbound.messaging.service;

import com.duozhi.outbound.common.config.Config;
import com.duozhi.outbound.common.exception.ApiError;
import com.duozhi.outbound.common.service.Transactions;
import com.duozhi.outbound.integration.support.Support;
import com.duozhi.outbound.messaging.dao.MessageStore;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Bounded private media store. Suppliers use approved asset mappings, never arbitrary remote fetch
 * URLs.
 */
@Service
public class MessageAssets {
    private final MessageStore db;
    private final Config config;
    private final Transactions tx;

    public MessageAssets(MessageStore db, Config config, Transactions tx) {
        this.db = db;
        this.config = config;
        this.tx = tx;
    }

    private Path root() throws IOException {
        String configured = config.get("MESSAGING_ASSET_ROOT", "");
        if (config.production() && configured.isBlank()) throw new ApiError(503, "生产素材目录须配置为持久存储");
        Path path =
                Path.of(
                                configured.isBlank()
                                        ? System.getProperty("java.io.tmpdir")
                                                + "/outbound-message-assets"
                                        : configured)
                        .toAbsolutePath()
                        .normalize();
        Files.createDirectories(path);
        return path;
    }

    private Path path(long tenant, String id) throws IOException {
        if (!id.matches("asset_[a-f0-9]{32}")) throw new ApiError(404, "素材不存在");
        return root().resolve(Long.toString(tenant)).resolve(id);
    }

    public Object upload(long tenant, String name, byte[] bytes, Instant expires) {
        if (name == null
                || name.isBlank()
                || name.length() > 128
                || bytes.length < 12
                || bytes.length > 5 * 1024 * 1024) throw new ApiError(422, "素材名称无效或文件超过 5 MiB");
        if (expires != null && !expires.isAfter(Instant.now()))
            throw new ApiError(422, "素材有效期必须在未来");
        String mime;
        if (bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G')
            mime = "image/png";
        else if (bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff)
            mime = "image/jpeg";
        else if (bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p')
            mime = "video/mp4";
        else throw new ApiError(422, "只接受 PNG、JPEG 和 MP4 素材");
        if (mime.startsWith("image/"))
            try (var input =
                    javax.imageio.ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = javax.imageio.ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new IOException();
                var reader = readers.next();
                try {
                    reader.setInput(input);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || width > 8192 || height > 8192)
                        throw new IOException();
                } finally {
                    reader.dispose();
                }
            } catch (IOException e) {
                throw new ApiError(422, "图片内容无效或尺寸超过 8192 像素");
            }
        String id = Messaging.id("asset"), hash = Support.hex(Support.sha(bytes));
        try {
            Path path = path(tenant, id);
            Files.createDirectories(path.getParent());
            Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
            try {
                return tx.run(
                        () -> {
                            var owner =
                                    db.one(
                                            "SELECT enabled FROM tenant WHERE id=? FOR UPDATE",
                                            tenant);
                            if (owner == null || !Boolean.TRUE.equals(owner.get("enabled")))
                                throw new ApiError(403, "企业已停用");
                            Long used =
                                    db.jdbc()
                                            .queryForObject(
                                                    "SELECT COALESCE(SUM(size_bytes),0) FROM msg_asset WHERE tenant_id=?",
                                                    Long.class,
                                                    tenant);
                            if (used + bytes.length > 200L * 1024 * 1024)
                                throw new ApiError(409, "素材存储配额不足");
                            db.jdbc()
                                    .update(
                                            "INSERT INTO msg_asset(id,tenant_id,name,mime,size_bytes,sha256,expires_at) VALUES(?,?,?,?,?,?,?)",
                                            id,
                                            tenant,
                                            name,
                                            mime,
                                            bytes.length,
                                            hash,
                                            expires == null
                                                    ? null
                                                    : java.sql.Timestamp.from(expires));
                            return get(tenant, id, false);
                        });
            } catch (RuntimeException failure) {
                Files.deleteIfExists(path);
                throw failure;
            }
        } catch (IOException e) {
            throw new ApiError(503, "素材存储不可用");
        }
    }

    public Map<String, Object> get(long tenant, String id, boolean ready) {
        var row = db.one("SELECT * FROM msg_asset WHERE tenant_id=? AND id=?", tenant, id);
        if (row == null) throw new ApiError(404, "素材不存在");
        if (ready
                && (!"READY".equals(row.get("state"))
                        || row.get("expires_at") != null
                                && !Messaging.instant(row.get("expires_at"))
                                        .isAfter(Instant.now())))
            throw new ApiError(409, "素材已停用或过期");
        return row;
    }

    public byte[] bytes(long tenant, String id) {
        get(tenant, id, false);
        try {
            return Files.readAllBytes(path(tenant, id));
        } catch (IOException e) {
            throw new ApiError(503, "素材文件不可用");
        }
    }

    public List<Map<String, Object>> list(long tenant) {
        return db.jdbc()
                .queryForList(
                        "SELECT * FROM msg_asset WHERE tenant_id=? ORDER BY created_at DESC,id DESC LIMIT 200",
                        tenant);
    }

    public Object disable(long tenant, String id) {
        get(tenant, id, false);
        db.jdbc()
                .update(
                        "UPDATE msg_asset SET state='DISABLED' WHERE tenant_id=? AND id=?",
                        tenant,
                        id);
        return get(tenant, id, false);
    }

    public void validate(long tenant, Map<String, Object> template) {
        for (String id : MessageContent.assets(template)) get(tenant, id, true);
    }
}
