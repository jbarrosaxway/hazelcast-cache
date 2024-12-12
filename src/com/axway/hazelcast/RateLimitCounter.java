
package com.axway.hazelcast;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.cp.IAtomicLong;
import com.hazelcast.spi.exception.DistributedObjectDestroyedException;
import com.vordel.trace.Trace;



public class RateLimitCounter {
    private final HazelcastInstance hazelcastInstance;
    private final String name;
    private volatile IAtomicLong atomicLong;
    private final Object lock = new Object();

    public RateLimitCounter(HazelcastInstance hazelcastInstance, String name) {
        this.hazelcastInstance = hazelcastInstance;
        this.name = name;
        this.atomicLong = initializeCounter();
    }

    private IAtomicLong initializeCounter() {
        try {
            return hazelcastInstance.getCPSubsystem().getAtomicLong(name);
        } catch (Exception e) {
            Trace.error("Erro ao inicializar contador: " + name, e);
            throw e;
        }
    }

    public long incrementAndGet(long delta) {
        int retries = 3;
        while (retries > 0) {
            try {
                synchronized (lock) {
                    if (atomicLong == null) {
                        atomicLong = initializeCounter();
                    }
                    return atomicLong.incrementAndGet();
                }
            } catch (DistributedObjectDestroyedException e) {
                retries--;
                if (retries > 0) {
                    atomicLong = null;
                    try {
                        Thread.sleep(100); // Pequeno delay antes de retry
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry", ie);
                    }
                } else {
                    Trace.error("Falha após tentativas de reinicializar contador: " + name, e);
                    throw e;
                }
            }
        }
        throw new RuntimeException("Falha ao incrementar contador após múltiplas tentativas");
    }

    public void reset() {
        synchronized (lock) {
            try {
                if (atomicLong != null) {
                    atomicLong.set(0);
                }
            } catch (DistributedObjectDestroyedException e) {
                atomicLong = initializeCounter();
                atomicLong.set(0);
            }
        }
    }

    public void destroy() {
        synchronized (lock) {
            try {
                if (atomicLong != null) {
                    atomicLong.destroy();
                    atomicLong = null;
                }
            } catch (Exception e) {
                Trace.error("Erro ao destruir contador: " + name, e);
            }
        }
    }

    public String getName() {
        return name;
    }
}
