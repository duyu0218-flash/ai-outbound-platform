package com.duozhi.outbound.marketing.dataset;

/**
 * Transport to the Ant DAS unified-entrance query (personal intention scores). The production
 * implementation is {@link HttpDasGateway}; tests substitute a scripted gateway.
 */
public interface DasGateway {
    /** result_code, req_msg_id and the raw data string as returned upstream. */
    record Response(String code, String requestId, String data) {}

    /** An upstream failure that carries a DAS result code (for example 10032 throttling). */
    class DasException extends RuntimeException {
        public final String code;

        public DasException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    boolean configured();

    /** Must not retry: charging semantics are unknown, a repeated call may be charged twice. */
    Response query(String phone, int topN, String authorizationRef) throws Exception;
}
