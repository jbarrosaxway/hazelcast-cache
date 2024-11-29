package com.axway.hazelcast.fake;

import java.util.concurrent.atomic.AtomicLong;

public class FakeAtomicCounter {
    private static final AtomicLong counter = new AtomicLong(0);
    
    public static long incrementAndGet() {
        return counter.incrementAndGet();
    }
    
    public static long get() {
        return counter.get();
    }
    
    public static void set(long value) {
        counter.set(value);
    }
}