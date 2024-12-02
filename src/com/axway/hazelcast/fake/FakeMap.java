package com.axway.hazelcast.fake;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class FakeMap<K, V> {
    private final ConcurrentHashMap<K, Entry<V>> internalMap = new ConcurrentHashMap<>();
    
    private static class Entry<V> {
        final V value;
        final long expirationTime;
        
        Entry(V value, long expirationTime) {
            this.value = value;
            this.expirationTime = expirationTime;
        }
        
        boolean isExpired() {
            return expirationTime > 0 && System.currentTimeMillis() > expirationTime;
        }
    }
    
    public V get(K key) {
        Entry<V> entry = internalMap.get(key);
        if (entry != null) {
            if (entry.isExpired()) {
                internalMap.remove(key);
                return null;
            }
            return entry.value;
        }
        return null;
    }
    
    public V put(K key, V value) {
        Entry<V> oldEntry = internalMap.put(key, new Entry<>(value, -1));
        return oldEntry != null ? oldEntry.value : null;
    }
    
    public V put(K key, V value, long ttl, TimeUnit timeUnit) {
        long expirationTime = System.currentTimeMillis() + timeUnit.toMillis(ttl);
        Entry<V> oldEntry = internalMap.put(key, new Entry<>(value, expirationTime));
        return oldEntry != null ? oldEntry.value : null;
    }
    
    public void clear() {
        internalMap.clear();
    }
    
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        Entry<V> entry = internalMap.get(key);
        if (entry != null) {
            if (entry.isExpired()) {
                internalMap.remove(key);
            } else {
                return entry.value;
            }
        }
        V value = mappingFunction.apply(key);
        if (value != null) {
            internalMap.put(key, new Entry<>(value, -1));
        }
        return value;
    }
    
    public void delete(K key) {
        internalMap.remove(key);
    }
    
    public boolean containsKey(K key) {
        Entry<V> entry = internalMap.get(key);
        if (entry != null) {
            if (entry.isExpired()) {
                internalMap.remove(key);
                return false;
            }
            return true;
        }
        return false;
    }
}