package com.duozhi.outbound.marketing.link;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/**
 * Public short-link entry point, no login (whitelisted in JwtSecurityConfiguration). It answers
 * from the in-process cache when it can and never waits for the click write. Kept free of other
 * dependencies so it can later move into its own deployment.
 */
@RestController
public class Redirect {
    private final Links links;
    private final Clicks clicks;

    public Redirect(Links links, Clicks clicks) {
        this.links = links;
        this.clicks = clicks;
    }

    @GetMapping("/s/{code}")
    public ResponseEntity<String> open(@PathVariable
    String code, HttpServletRequest request) {
        var link = links.resolve(code);
        if (link == null) return page(HttpStatus.NOT_FOUND, "链接不存在", "请检查短信中的链接是否完整。");
        Instant now = Instant.now();
        if (link.expiresAt() != null && !link.expiresAt().isAfter(now))
            return page(HttpStatus.GONE, "活动已结束", "感谢关注，本次活动已结束。");
        String ua = request.getHeader(HttpHeaders.USER_AGENT);
        boolean wechat = ua != null && ua.contains("MicroMessenger");
        clicks.offer(
                new Clicks.Click(
                        link.code(), now, Clicks.ipPrefix(request.getRemoteAddr()), ua, wechat));
        if (wechat) return page(HttpStatus.OK, "请在浏览器中打开", "点击右上角「···」，选择「在浏览器打开」继续访问。");
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, link.target())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Referrer-Policy", "no-referrer")
                .build();
    }

    private static ResponseEntity<String> page(HttpStatus status, String title, String text) {
        return ResponseEntity.status(status)
                .contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Referrer-Policy", "no-referrer")
                .body(
                        "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\"><meta"
                                + " name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>"
                                + title
                                + "</title><style>body{font-family:-apple-system,'PingFang SC',sans-serif;margin:0;padding:64px"
                                + " 24px;text-align:center;color:#303133;background:#f5f7fa}h1{font-size:20px}p{color:#606266;line-height:1.6}</style></head><body><h1>"
                                + title
                                + "</h1><p>"
                                + text
                                + "</p></body></html>");
    }
}
