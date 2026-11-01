package com.duozhi.outbound.marketing.common;

/**
 * Per-recipient short links. Implemented by the link module (P2). Senders mint before creating the
 * message (the link goes into its text) and bind the message id once it exists.
 */
public interface LinkMinter {
    record ShortLink(String code, String url) {}

    /**
     * @param domainPool pool name, or null for the tenant's default pool
     * @param landingUrl must be on the tenant's approved landing-domain list
     */
    ShortLink mint(
            long tenant,
            String domainPool,
            String landingUrl,
            String phoneHash,
            Long contactId,
            ChannelSender.Origin origin);

    void bindMessage(long tenant, String code, String messageId);
}
