package com.duozhi.outbound;

import java.util.*;

/** Small collection factories for the Java 8 baseline. */
final class Values {
    private Values() {}
    @SuppressWarnings("unchecked")
    static <K,V> Map<K,V> map(Object... pairs) {
        if(pairs.length%2!=0)throw new IllegalArgumentException("key/value pairs required");
        Map<K,V> result=new LinkedHashMap<K,V>();
        for(int i=0;i<pairs.length;i+=2){
            K key=(K)Objects.requireNonNull(pairs[i]);V value=(V)Objects.requireNonNull(pairs[i+1]);
            if(result.put(key,value)!=null)throw new IllegalArgumentException("duplicate key");
        }
        return Collections.unmodifiableMap(result);
    }
    @SafeVarargs
    static <T> List<T> list(T... items){return Collections.unmodifiableList(Arrays.asList(items));}
    @SafeVarargs
    static <T> Set<T> set(T... items){return Collections.unmodifiableSet(new LinkedHashSet<T>(Arrays.asList(items)));}
}
