package com.duozhi.outbound.common.service;

import java.time.Duration;

/**
 * Transaction serialization boundary. Deployment is single-instance until replaced and requalified.
 */
public interface PlatformLock {
    /** Transient contention must defer durable work without consuming a business attempt. */
    final class Unavailable extends com.duozhi.outbound.common.exception.ApiError {
        public Unavailable(boolean interrupted) {
            super(503, interrupted ? "PLATFORM_LOCK_INTERRUPTED" : "PLATFORM_LOCK_TIMEOUT");
        }
    }

    interface Handle extends AutoCloseable {
        @Override
        void close();
    }

    Handle lock(long key);

    /** Bounded, interruptible acquisition; failure must never be treated as ownership. */
    Handle lock(long key, Duration timeout);

    /** A separate namespace, with immediate acquisition; null means another thread owns it. */
    Handle tryLock(int namespace, int key);
}
