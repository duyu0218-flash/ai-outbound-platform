package com.duozhi.outbound;
/** Transaction serialization boundary. Deployment is single-instance until replaced and requalified. */
public interface PlatformLock {
    interface Handle extends AutoCloseable { @Override void close(); }
    Handle lock(long key);
    /** A separate namespace, with immediate acquisition; null means another thread owns it. */
    Handle tryLock(int namespace,int key);
}
