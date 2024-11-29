package com.axway.hazelcast.fake;

import java.util.concurrent.ConcurrentHashMap;

public class FakeHazelcast {
    private static final ConcurrentHashMap<String, FakeMap<?, ?>> maps = new ConcurrentHashMap<>();
    
    @SuppressWarnings("unchecked")
    public static <K, V> FakeMap<K, V> getMap(String name) {
        return (FakeMap<K, V>) maps.computeIfAbsent(name, k -> new FakeMap<>());
    }
    
    public static void clearAll() {
        maps.clear();
    }
    
    public static boolean isRunning() {
        return true;
    }
}