package com.axway.hazelcast.fake;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FakeMap<K, V> {
    private final ConcurrentHashMap<K, V> internalMap = new ConcurrentHashMap<>();
    
    public V get(K key) {
        return internalMap.get(key);
    }
    
    public V put(K key, V value) {
        return internalMap.put(key, value);
    }
    
    public void clear() {
        internalMap.clear();
    }
    
    public boolean containsKey(K key) {
        return internalMap.containsKey(key);
    }
    
    public V remove(K key) {
        return internalMap.remove(key);
    }
    
    public int size() {
        return internalMap.size();
    }
    
    public void putAll(Map<? extends K, ? extends V> map) {
        internalMap.putAll(map);
    }
}