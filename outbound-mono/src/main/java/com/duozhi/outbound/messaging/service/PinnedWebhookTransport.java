package com.duozhi.outbound.messaging.service;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.*;
import org.springframework.stereotype.Component;

/**
 * Resolve once, validate every address, then connect to that exact address with TLS hostname
 * checks.
 */
@Component
public class PinnedWebhookTransport implements WebhookTransport {
    private static final com.duozhi.outbound.integration.support.BoundedVirtualExecutor DNS =
            new com.duozhi.outbound.integration.support.BoundedVirtualExecutor("webhook-dns-", 4);
    private static final java.util.concurrent.ScheduledThreadPoolExecutor DEADLINES = deadlines();

    private static java.util.concurrent.ScheduledThreadPoolExecutor deadlines() {
        var timer =
                new java.util.concurrent.ScheduledThreadPoolExecutor(
                        1, Thread.ofPlatform().daemon().name("webhook-deadlines").factory());
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    private InetAddress[] resolve(String host) throws Exception {
        var future = DNS.submit(() -> InetAddress.getAllByName(host));
        try {
            return future.get(3, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            future.cancel(true);
        }
    }

    public static void validate(URI uri) throws IOException {
        if (!"https".equals(uri.getScheme())
                || uri.getHost() == null
                || uri.getHost().endsWith(".")
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.toASCIIString().length() > 2048)
            throw new IOException("invalid webhook URL");
    }

    public static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        int first = b[0] & 255;
        if (b.length == 4) {
            int second = b[1] & 255;
            return first != 0
                    && first != 10
                    && first != 127
                    && first < 224
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 169 && second == 254)
                    && !(first == 172 && second >= 16 && second <= 31)
                    && !(first == 192 && (second == 168 || second == 0 || second == 2))
                    && !(first == 198 && (second == 18 || second == 19 || second == 51))
                    && !(first == 203 && second == 0);
        }
        // Global unicast only; reject ULA, transition, documentation and embedded IPv4 ranges.
        return (first & 0xe0) == 0x20
                && !(first == 0x20
                        && (b[1] & 255) == 0x01
                        && ((b[2] & 255) == 0 || ((b[2] & 255) == 0x0d && (b[3] & 255) == 0xb8)))
                && !(first == 0x20 && (b[1] & 255) == 0x02);
    }

    @Override
    public Reply post(URI uri, Map<String, String> headers, byte[] body) throws Exception {
        validate(uri);
        InetAddress[] addresses = resolve(uri.getHost());
        if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(a -> !publicAddress(a)))
            throw new IOException("webhook address is not public");
        try (var raw = new Socket()) {
            var deadline =
                    DEADLINES.schedule(
                            () ->
                                    Thread.ofVirtual()
                                            .start(
                                                    () -> {
                                                        try {
                                                            raw.close();
                                                        } catch (IOException ignored) {
                                                        }
                                                    }),
                            5,
                            java.util.concurrent.TimeUnit.SECONDS);
            try {
                raw.connect(new InetSocketAddress(addresses[0], 443), 3000);
                raw.setSoTimeout(5000);
                try (var tls =
                        (SSLSocket)
                                ((SSLSocketFactory) SSLSocketFactory.getDefault())
                                        .createSocket(raw, uri.getHost(), 443, true)) {
                    var parameters = tls.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    tls.setSSLParameters(parameters);
                    tls.setSoTimeout(5000);
                    tls.startHandshake();
                    String target = uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
                    if (uri.getRawQuery() != null) target += "?" + uri.getRawQuery();
                    var head =
                            new StringBuilder("POST ")
                                    .append(target)
                                    .append(" HTTP/1.1\r\nHost: ")
                                    .append(uri.getHost())
                                    .append(
                                            "\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: ")
                                    .append(body.length)
                                    .append("\r\n");
                    for (var h : headers.entrySet()) {
                        if (!h.getKey().matches("[A-Za-z0-9-]+")
                                || h.getValue().contains("\r")
                                || h.getValue().contains("\n"))
                            throw new IOException("invalid webhook header");
                        head.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
                    }
                    var out = tls.getOutputStream();
                    out.write(head.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
                    out.write(body);
                    out.flush();
                    var in = tls.getInputStream();
                    String status = line(in);
                    if (!status.matches("HTTP/1\\.[01] [0-9]{3}.*"))
                        throw new IOException("invalid webhook response");
                    String challenge = "";
                    int total = status.length();
                    for (String line; (line = line(in)).length() > 0; ) {
                        total += line.length();
                        if (total > 16384) throw new IOException("webhook headers too large");
                        int colon = line.indexOf(':');
                        if (colon > 0
                                && line.substring(0, colon).equalsIgnoreCase("X-Webhook-Challenge"))
                            challenge = line.substring(colon + 1).strip();
                    }
                    return new Reply(Integer.parseInt(status.substring(9, 12)), challenge);
                }
            } finally {
                deadline.cancel(false);
            }
        }
    }

    private String line(InputStream in) throws IOException {
        var bytes = new ByteArrayOutputStream();
        for (int ch; (ch = in.read()) != -1; ) {
            if (ch == '\n')
                return bytes.toString(StandardCharsets.US_ASCII).replaceFirst("\\r$", "");
            if (bytes.size() >= 4096) throw new IOException("webhook line limit");
            bytes.write(ch);
        }
        throw new EOFException();
    }
}
