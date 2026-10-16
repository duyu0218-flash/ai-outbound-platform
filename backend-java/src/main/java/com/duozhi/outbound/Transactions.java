package com.duozhi.outbound;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class Transactions {
    private final Store store;
    private final TransactionTemplate template;
    public Transactions(Store store, PlatformTransactionManager manager) { this.store = store; this.template = new TransactionTemplate(manager); }
    public <T> T run(Supplier<T> action) { return template.execute(status -> action.get()); }
    public void lock(long key) {
        if (!store.lock().isEmpty()) store.jdbc().queryForList("SELECT pg_advisory_xact_lock(?)", key);
        else store.jdbc().update("UPDATE tenant SET id=id WHERE id=-1");
    }
    public void identity(String key) { lock(ByteBuffer.wrap(JsonSupport.digest(key)).getLong()); }
    public void platform() { lock(718492061L); }
    public Map<String, Object> tenant(long id) {
        if (store.lock().isEmpty()) store.jdbc().update("UPDATE tenant SET id=id WHERE id=?", id);
        Map<String, Object> result = store.one("tenant", "WHERE id=?" + store.lock(), id);
        if (result == null) throw new ApiError(404, "tenant not found");
        return result;
    }
    public Map<String, Object> call(Object id) {
        Object bound = store.encode("callsession", "id", id);
        if (store.lock().isEmpty()) store.jdbc().update("UPDATE callsession SET id=id WHERE id=?", bound);
        return store.one("callsession", "WHERE id=?" + store.lock(), bound);
    }
}
