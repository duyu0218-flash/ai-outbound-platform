package com.duozhi.outbound.marketing.link;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.cert.X509Certificate;
import java.time.Instant;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Real DNS lookup and TLS handshake on port 443; enabled only by MARKETING_LINK_PROBE=network. */
@Component
@ConditionalOnProperty(name = "MARKETING_LINK_PROBE", havingValue = "network")
public class NetworkDomainNetwork implements DomainNetwork {
    @Override
    public String dnsFailure(String domain) {
        try {
            return InetAddress.getAllByName(domain).length == 0 ? "域名无解析记录" : null;
        } catch (Exception e) {
            return "域名无法解析";
        }
    }

    @Override
    public Instant certificateExpiry(String domain) {
        try (var socket = (SSLSocket) SSLContext.getDefault().getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress(domain, 443), 3000);
            socket.setSoTimeout(3000);
            var params = socket.getSSLParameters();
            params.setServerNames(java.util.List.of(new javax.net.ssl.SNIHostName(domain)));
            socket.setSSLParameters(params);
            socket.startHandshake();
            var chain = socket.getSession().getPeerCertificates();
            return chain.length > 0 && chain[0] instanceof X509Certificate x
                    ? x.getNotAfter().toInstant()
                    : null;
        } catch (Exception e) {
            return null;
        }
    }
}
