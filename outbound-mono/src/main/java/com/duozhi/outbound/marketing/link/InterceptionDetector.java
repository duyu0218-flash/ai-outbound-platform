package com.duozhi.outbound.marketing.link;

/**
 * Whether WeChat, carriers or handset vendors intercept a domain. Real vendor APIs plug in here;
 * the default is {@link MockInterceptionDetector}, selected by MARKETING_LINK_INTERCEPTION=mock.
 */
public interface InterceptionDetector {
    /** Null when the domain is not intercepted; otherwise who intercepts it and why. */
    String intercepted(String domain);
}
