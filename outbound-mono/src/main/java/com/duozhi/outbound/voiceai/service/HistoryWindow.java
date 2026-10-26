package com.duozhi.outbound.voiceai.service;

import java.util.*;

/** Keep whole recent records within both count and character budgets; never fabricate summaries. */
public final class HistoryWindow {
    private HistoryWindow() {}

    public static <T> List<T> recent(
            List<T> history,
            int records,
            int characters,
            java.util.function.ToIntFunction<T> length) {
        var result = new ArrayList<T>();
        int used = 0;
        for (int i = history.size() - 1; i >= 0 && result.size() < records; i--) {
            T item = history.get(i);
            int size = length.applyAsInt(item);
            if (size > characters - used) break;
            result.add(item);
            used += size;
        }
        Collections.reverse(result);
        return List.copyOf(result);
    }
}
