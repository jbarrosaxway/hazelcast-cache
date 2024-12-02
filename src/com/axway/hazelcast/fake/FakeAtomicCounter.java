package com.axway.hazelcast.fake;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class FakeAtomicCounter {
    private static final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    
    public static AtomicLong getCounter(String name) {
        return counters.computeIfAbsent(name, k -> new AtomicLong(0));
    }
    
    public static void clear() {
        counters.clear();
    }
}