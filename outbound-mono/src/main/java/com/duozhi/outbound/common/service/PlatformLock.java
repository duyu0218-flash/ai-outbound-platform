package com.duozhi.outbound.common.service;

import java.time.Duration;

/**
 * Transaction serialization boundary. Deployment is single-instance until replaced and requalified.
 */
public interface PlatformLock {
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
