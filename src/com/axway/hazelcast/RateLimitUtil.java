package com.axway.hazelcast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import com.axway.apigw.cassandra.api.ClusterConnectionPool;
import com.axway.apigw.cassandra.api.constants.TableEnum;
import com.axway.apigw.cassandra.factory.CassandraObjectFactory;
import com.datastax.driver.core.ColumnDefinitions;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Session;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.cp.IAtomicLong;
import com.hazelcast.map.IMap;
import com.vordel.circuit.Message;
import com.vordel.common.Dictionary;
import com.vordel.el.Selector;
import com.vordel.trace.Trace;

public class RateLimitUtil {
    private static volatile RateLimitUtil instance = null;
    private final ScheduledExecutorService scheduler;
    private volatile HazelcastInstance hazelcastInstance;

    // Usar ThreadLocal para evitar vazamentos de memória
    private static final ThreadLocal<Map<String, Object>> threadLocalCache = 
        ThreadLocal.withInitial(HashMap::new);

    private static final String RATE_LIMITER_PREFIX = "rate-limit.";
    
    // Constantes para configuração dos schedulers
    private static final int INITIAL_DELAY = 1;
    private static final int COUNTER_CLEANUP_PERIOD = 1;
    private static final int CACHE_CLEANUP_PERIOD = 30;
    
    // Cache para PreparedStatements
    private final Map<String, PreparedStatement> preparedStatementCache = new ConcurrentHashMap<>();


    
    private RateLimitUtil() {
        scheduler = Executors.newScheduledThreadPool(2, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setDaemon(true);
                thread.setName("RateLimit-Scheduler-" + thread.getId());
                return thread;
            }
        });
        
        // Agendar limpeza de contadores expirados (a cada 1 hora)
        scheduler.scheduleAtFixedRate(
            this::cleanupExpiredCounters, 
            INITIAL_DELAY, 
            COUNTER_CLEANUP_PERIOD, 
            TimeUnit.HOURS
        );
        
        // Agendar limpeza do cache de PreparedStatements (a cada 30 minutos)
        scheduler.scheduleAtFixedRate(
            this::cleanupPreparedStatementCache, 
            INITIAL_DELAY, 
            CACHE_CLEANUP_PERIOD, 
            TimeUnit.MINUTES
        );

        // Adicionar shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                destroy();
            } catch (Exception e) {
                Trace.error("Erro durante shutdown do RateLimitUtil", e);
            }
        }));
    }
    
    
    private RateLimitUtil(HazelcastInstance hazelcastInstance) {
        this.hazelcastInstance = hazelcastInstance;
		this.scheduler = null;
        
        Trace.info("RateLimitUtil iniciado com instância Hazelcast fornecida");
    }

    public static RateLimitUtil getInstance(HazelcastInstance hazelcastInstance) {
        if (instance == null) {
            synchronized (RateLimitUtil.class) {
                if (instance == null) {
                    instance = new RateLimitUtil(hazelcastInstance);
                }
            }
        }
        return instance;
    }


    private HazelcastInstance getHazelcastInstance() {
        // Não precisamos mais procurar a instância, pois ela já foi injetada
        if (hazelcastInstance == null) {
            Trace.error("Não foi possível encontrar a instância do Hazelcast.");
        }
        return hazelcastInstance;
    }
    
    public boolean performRateLimitValidations(Message msg, String userKeySelectorParam) {
    	return validateRateLimit(msg, userKeySelectorParam);
    }

    private TimeUnit mapStringToTimeUnit(String timeUnitStr) {
        try {
            return TimeUnit.valueOf(timeUnitStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid TimeUnit string: " + timeUnitStr);
        }
    }
    
    private String getFullyQualifiedTable(String keySpace, String table) {
        return keySpace + "." + table;
    }
    
    // Melhorar o gerenciamento de cache
    private List<Map<String, Object>> getRateLimit(Dictionary msg) {
        IMap<String, List<Map<String, Object>>> cache = getHazelcastInstance().getMap("rateLimitCache");
        String cacheKey = "rateLimits:cassandra";

        return cache.computeIfAbsent(cacheKey, k -> {
            try {
                return fetchRateLimitsFromCassandra();
            } catch (Exception e) {
                Trace.error("Erro ao buscar rate limits do Cassandra", e);
                return new ArrayList<>();
            }
        });
    }

    private List<Map<String, Object>> fetchRateLimitsFromCassandra() {
        ClusterConnectionPool cassandraConnectionPool = CassandraObjectFactory.getClusterConnectionPool();
        Session session = cassandraConnectionPool.getSession();
        String keyspace = cassandraConnectionPool.getKeySpace();
        String table = "\"Axway_RateLimit\"".toLowerCase();
        
        String select = "SELECT * FROM " + getFullyQualifiedTable(keyspace, table);
        
        // Usar cache de PreparedStatement
        PreparedStatement ps = preparedStatementCache.computeIfAbsent(select, 
            k -> session.prepare(k).setConsistencyLevel(getConsistencyLevel(keyspace, table)));
            
        ResultSet rs = session.execute(ps.bind());
        
        return processResultSet(rs);
    }

    private List<Map<String, Object>> processResultSet(ResultSet rs) {
        if (rs.isExhausted()) {
            return new ArrayList<>();
        }

        List<Map<String, Object>> results = new ArrayList<>();
        ColumnDefinitions columnDefs = rs.getColumnDefinitions();
        
        for (com.datastax.driver.core.Row row : rs) {
            Map<String, Object> result = new HashMap<>();
            for (ColumnDefinitions.Definition def : columnDefs) {
                String name = def.getName();
                if (!TableEnum.KEY.getValue().equals(name)) {
                    String value = row.getString("\"" + name + "\"");
                    if (value != null) {
                        result.put(name, value.replaceAll("^\"|\"$", ""));
                    }
                }
            }
            results.add(result);
        }
        return results;
    }

    // Método para limpar ThreadLocal
    public void cleanupThreadLocal() {
        try {
            Map<String, Object> cache = threadLocalCache.get();
            if (cache != null) {
                cache.clear();
            }
            threadLocalCache.remove();
        } catch (Exception e) {
            Trace.error("Erro ao limpar ThreadLocal: " + e.getMessage());
        }
    }



    public boolean validateRateLimit(Message msg, String userKeySelectorParam) {
        List<Map<String, Object>> rateLimits = getRateLimit(msg);
        Trace.debug("Rate limits: " + rateLimits.toString());
        long currentTime = System.currentTimeMillis();

        if (rateLimits.isEmpty()) {
            return true;
        } else {
            for (Map<String, Object> rateLimit : rateLimits) {
                String rateLimitKeyBD = (String) rateLimit.get("rateLimitkey");
                Trace.debug("Rate limit key: " + rateLimitKeyBD);
                String rateLimitKeySelector = (String) rateLimit.get("rateLimitkeySelector");
                Trace.debug("Rate limit key selector: " + rateLimitKeySelector);
                
                String[] result = isRateLimitApplicable(msg, rateLimitKeyBD, rateLimitKeySelector, userKeySelectorParam);
                boolean isApplicable = Boolean.parseBoolean(result[0]);
                String selectorValue = result[1];
                
                if (isApplicable) {
                    boolean isValid = true;
                    Number rateLimitValue = Long.parseLong((String) rateLimit.get("rateLimit"));
                    Integer timeInterval = Integer.parseInt((String) rateLimit.get("timeInterval"));
                    String timeUnitStr = (String) rateLimit.get("timeUnit");
                    TimeUnit timeUnit = mapStringToTimeUnit(timeUnitStr);
                    
                    String counterName = RATE_LIMITER_PREFIX + selectorValue;
                    IAtomicLong counter = getOrCreateCounter(counterName);
                    
                    if (counter == null) {
                        // Fallback em caso de erro com CP Subsystem
                        Trace.info("CP Subsystem não disponível, permitindo requisição");
                        return true;
                    }
                    
                    // Verifica se o contador existe e está dentro do período válido
                    IMap<String, Long> expirationMap = hazelcastInstance.getMap(RATE_LIMITER_PREFIX + "expiration");
                    Long lastResetTime = expirationMap.get(counterName);
                    
                    if (lastResetTime == null || (currentTime - lastResetTime) >= timeUnit.toMillis(timeInterval)) {
                        // Reset do contador se o período expirou
                        counter.set(0);
                        expirationMap.put(counterName, currentTime, timeInterval, timeUnit);
                        Trace.debug("Counter reset for " + counterName);
                    }
                    
                    // Incrementa o contador
                    long currentCount = counter.incrementAndGet();
                    Trace.debug("Current count for " + counterName + ": " + currentCount);
                    
                    if (currentCount > rateLimitValue.longValue()) {
                        Trace.debug("Rate limit exceeded for " + counterName);
                        isValid = false;
                    }
                    
                    // Atualiza métricas na mensagem
                    msg.put(counterName + ".is-exceeded", !isValid);
                    long remain = rateLimitValue.longValue() - currentCount;
                    msg.put(counterName + ".remain", !isValid ? 0 : remain);
                    
                    return isValid;
                }
            }
            return true;
        }
    }
    
    private void cleanupExpiredCounters() {
        IMap<String, Long> expirationMap = getHazelcastInstance().getMap(RATE_LIMITER_PREFIX+"expiration");
        for (Map.Entry<String, Long> entry : expirationMap.entrySet()) {
            if (System.currentTimeMillis() - entry.getValue() >= TimeUnit.HOURS.toMillis(1)) {
                String counterName = entry.getKey();
                IAtomicLong counter = getHazelcastInstance().getCPSubsystem().getAtomicLong(counterName);
                counter.destroy();
                expirationMap.delete(entry.getKey());
            }
        }
    }


    private String[] isRateLimitApplicable(Message msg, String rateLimitKeyBD, String rateLimitKeySelector,
            String userKeySelectorParam) {
        Selector<String> keySelector = new Selector<>(rateLimitKeySelector, String.class);
        Selector<String> key = new Selector<>(rateLimitKeyBD, String.class);
        Selector<String> userKeySelector = new Selector<>(userKeySelectorParam, String.class);

        String selectorValue = keySelector.substitute(msg);
        String selectorKey = key.substitute(msg);
        String userKey = userKeySelector.substitute(msg);

        Trace.debug("Evaluator: " + selectorKey + ".equals(" + selectorValue + ") && " + selectorKey + ".equals("
                + userKey + ")");

        // Retorna um array com o resultado da validação e o selectorValue
        return new String[]{String.valueOf(selectorKey.equals(selectorValue) && selectorKey.equals(userKey)), selectorValue};
    }
	

    
    public void destroy() {
        try {
            Trace.info("Iniciando destruição do RateLimitUtil...");
        
            // Desligar o scheduler
            if (scheduler != null) {
                scheduler.shutdown();
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            }
            
            // Limpar cache e fechar recursos do Cassandra
            synchronized (preparedStatementCache) {
                preparedStatementCache.clear();
            }
            closeCassandraResources();
            
            // Limpar contadores e mapas do Hazelcast
            HazelcastInstance hz = getHazelcastInstance();
            if (hz != null) {
                try {
                    // Limpar mapa de expiração
                    IMap<String, Long> expirationMap = hz.getMap(RATE_LIMITER_PREFIX + "expiration");
                    if (expirationMap != null) {
                        expirationMap.keySet().forEach(counterName -> {
                            try {
                                IAtomicLong counter = hz.getCPSubsystem().getAtomicLong(counterName);
                                if (counter != null) {
                                    counter.destroy();
                                }
                            } catch (Exception e) {
                                Trace.error("Erro ao destruir contador: " + counterName, e);
                            }
                        });
                        expirationMap.clear();
                    }
                    
                    // Limpar cache de rate limits
                    IMap<String, List<Map<String, Object>>> rateCache = hz.getMap("rateLimitCache");
                    if (rateCache != null) {
                        rateCache.clear();
                    }
                } catch (Exception e) {
                    Trace.error("Erro ao limpar mapas do Hazelcast", e);
                }
            }
            
            Trace.info("RateLimitUtil destruído com sucesso.");
            
        } catch (Exception e) {
            Trace.error("Erro durante destroy do RateLimitUtil", e);
            throw new RuntimeException("Falha ao destruir RateLimitUtil", e);
        }
    }

    private IAtomicLong getOrCreateCounter(String counterName) {
        try {
            return getHazelcastInstance().getCPSubsystem().getAtomicLong(counterName);
        } catch (Exception e) {
            Trace.error("Erro ao obter contador: " + counterName, e);
            return null;
        }
    }

    private ConsistencyLevel getConsistencyLevel(String keyspace, String table) {
        try {
            // Primeiro, tenta obter a consistência específica da tabela
            String tableSpecificLevel = System.getProperty(
                String.format("cassandra.consistency.%s.%s", keyspace, table),
                System.getProperty("cassandra.consistency.default", "LOCAL_QUORUM")
            );
            
            return ConsistencyLevel.valueOf(tableSpecificLevel.toUpperCase());
        } catch (IllegalArgumentException e) {
            Trace.info("Nível de consistência inválido configurado, usando LOCAL_QUORUM como fallback");
            return ConsistencyLevel.LOCAL_QUORUM;
        }
    }

    private void closeCassandraResources() {
        try {
            ClusterConnectionPool cassandraConnectionPool = CassandraObjectFactory.getClusterConnectionPool();
            if (cassandraConnectionPool != null) {
                Session session = cassandraConnectionPool.getSession();
                if (session != null && !session.isClosed()) {
                    session.close();
                }
            }
        } catch (Exception e) {
            Trace.error("Erro ao fechar recursos do Cassandra", e);
        }
    }


    private void cleanupPreparedStatementCache() {
        try {
            Trace.debug("Iniciando limpeza do cache de PreparedStatements");
            synchronized (preparedStatementCache) {
                int sizeBefore = preparedStatementCache.size();
                preparedStatementCache.clear();
                Trace.info("Cache de PreparedStatements limpo. Tamanho anterior: " + sizeBefore);
            }
        } catch (Exception e) {
            Trace.error("Erro durante limpeza do cache de PreparedStatements", e);
        }
    }
			    
}