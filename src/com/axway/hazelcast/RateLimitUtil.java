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
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.cp.IAtomicLong;
import com.hazelcast.map.IMap;
import com.vordel.circuit.Message;
import com.vordel.common.Dictionary;
import com.vordel.el.Selector;
import com.vordel.trace.Trace;

public class RateLimitUtil {
    private static final String RATE_LIMIT_TYPE_MAP = "map";
    private static volatile RateLimitUtil instance = null;
    private final ScheduledExecutorService scheduler;
    private volatile HazelcastInstance hazelcastInstance;
    
    private static final String CACHE_MAP_NAME = "rateLimitCache";
    private static final String CACHE_KEY = "rateLimits:cassandra";

    // Cache local para reduzir acessos ao Hazelcast
    private volatile List<Map<String, Object>> localRateLimitCache;
    private volatile long lastCacheUpdate;
    private static final long CACHE_TTL = TimeUnit.MINUTES.toMillis(5); // 5 minutos de TTL

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
	private String rateLimitType;


    
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

    private RateLimitUtil(HazelcastInstance hazelcastInstance, String rateLimitType) {
        this.hazelcastInstance = hazelcastInstance;
        this.scheduler = null;
        this.rateLimitType = rateLimitType;
                
        Trace.info("RateLimitUtil iniciado com instância Hazelcast fornecida. Tipo de rate limit: " + rateLimitType);
    }

    public static RateLimitUtil getInstance(HazelcastInstance hazelcastInstance, String rateLimitType) {
        if (instance == null) {
            synchronized (RateLimitUtil.class) {
                if (instance == null) {
                    instance = new RateLimitUtil(hazelcastInstance, rateLimitType);
                }
            }
        }
        return instance;
    }

	public static RateLimitUtil getInstance() {
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
    
    private List<Map<String, Object>> getRateLimit(Dictionary msg) {
        // Primeiro, tenta usar o cache local
        List<Map<String, Object>> currentCache = localRateLimitCache;
        long currentTime = System.currentTimeMillis();
        
        if (currentCache != null && (currentTime - lastCacheUpdate) < CACHE_TTL) {
            return currentCache;
        }
        
        // Se não há cache local ou expirou, busca do Hazelcast
        try {
            IMap<String, List<Map<String, Object>>> cache = getHazelcastInstance().getMap(CACHE_MAP_NAME);
            List<Map<String, Object>> rateLimits = cache.get(CACHE_KEY);
            
            if (rateLimits == null) {
                // Se não existe no Hazelcast, busca do Cassandra
                rateLimits = fetchAndCacheRateLimits(cache);
            }
            
            // Atualiza o cache local
            localRateLimitCache = rateLimits;
            lastCacheUpdate = currentTime;
            
            return rateLimits;
            
        } catch (Exception e) {
            Trace.error("Erro ao obter rate limits do cache", e);
            // Em caso de erro, retorna o cache local se disponível, ou lista vazia
            return currentCache != null ? currentCache : new ArrayList<>();
        }
    }
    
    private List<Map<String, Object>> fetchAndCacheRateLimits(IMap<String, List<Map<String, Object>>> cache) {
        try {
            List<Map<String, Object>> rateLimits = fetchRateLimitsFromCassandra();
            if (!rateLimits.isEmpty()) {
                cache.set(CACHE_KEY, rateLimits, CACHE_TTL, TimeUnit.MILLISECONDS);
            }
            return rateLimits;
        } catch (Exception e) {
            Trace.error("Erro ao buscar rate limits do Cassandra", e);
            return new ArrayList<>();
        }
    }

    private List<Map<String, Object>> fetchRateLimitsFromCassandra() {
        List<Map<String, Object>> results = new ArrayList<>();
        ClusterConnectionPool connectionPool = null;
        
        try {
            connectionPool = CassandraObjectFactory.getClusterConnectionPool();
            final Session session = connectionPool.getSession();
            String keyspace = connectionPool.getKeySpace();
            String table = "\"Axway_RateLimit\"".toLowerCase();
            
            String select = "SELECT * FROM " + getFullyQualifiedTable(keyspace, table);
            
            // Usar cache de PreparedStatement
            PreparedStatement ps = preparedStatementCache.computeIfAbsent(select, 
                k -> session.prepare(k).setConsistencyLevel(getConsistencyLevel(keyspace, table)));
                
            ResultSet rs = session.execute(ps.bind());
            
            if (!rs.isExhausted()) {
                processResultSet(rs, results);
            }
            
        } catch (Exception e) {
            Trace.error("Erro ao buscar dados do Cassandra", e);
        }
        
        return results;
    }
    
    private void processResultSet(ResultSet rs, List<Map<String, Object>> results) {
        ColumnDefinitions columnDefs = rs.getColumnDefinitions();
        
        for (Row row : rs) {
            Map<String, Object> result = new HashMap<>();
            
            for (ColumnDefinitions.Definition def : columnDefs) {
                String name = def.getName();
                if (!TableEnum.KEY.getValue().equals(name)) {
                    String quotedName = "\"" + name + "\"";
                    String value = row.getString(quotedName);
                    if (value != null) {
                        result.put(name, value.replaceAll("^\"|\"$", ""));
                    }
                }
            }
            
            if (!result.isEmpty()) {
                results.add(result);
            }
        }
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
        // Usar a variável de instância em vez de tentar obter da configuração
        if (RATE_LIMIT_TYPE_MAP.equalsIgnoreCase(this.rateLimitType)) {
            return validateRateLimitUsingMap(msg, userKeySelectorParam);
        } else {
            return validateRateLimitUsingAtomic(msg, userKeySelectorParam);
        }
    }


    public boolean validateRateLimitUsingAtomic(Message msg, String userKeySelectorParam) {
        // Cache de StringBuilder para reutilização
        StringBuilder sb = new StringBuilder(128);
        
        // Obter rateLimits uma única vez
        List<Map<String, Object>> rateLimits = getRateLimit(msg);
        if (rateLimits.isEmpty()) {
            return true;
        }

        long currentTime = System.currentTimeMillis();
        
        // Classe auxiliar para evitar array
        RateLimitResult result = new RateLimitResult();
        
        for (Map<String, Object> rateLimit : rateLimits) {
            // Extrair valores do map uma única vez para evitar múltiplos gets
            String rateLimitKeyBD = (String) rateLimit.get("rateLimitkey");
            String rateLimitKeySelector = (String) rateLimit.get("rateLimitkeySelector");
            
            if (Trace.isDebugEnabled()) {
                sb.setLength(0);
                Trace.debug(sb.append("Rate limit key: ").append(rateLimitKeyBD).toString());
                sb.setLength(0);
                Trace.debug(sb.append("Rate limit key selector: ").append(rateLimitKeySelector).toString());
            }
            
            // Usar objeto reutilizável ao invés de array
            checkRateLimitApplicability(msg, rateLimitKeyBD, rateLimitKeySelector, userKeySelectorParam, result);
            
            if (result.isApplicable()) {
                // Parse valores numéricos de forma otimizada
                long rateLimitValue = parseLongSafely(rateLimit.get("rateLimit"));
                int timeInterval = parseIntSafely(rateLimit.get("timeInterval"));
                TimeUnit timeUnit = mapStringToTimeUnit((String) rateLimit.get("timeUnit"));
                
                // Construir counterName reutilizando StringBuilder
                sb.setLength(0);
                String counterName = sb.append(RATE_LIMITER_PREFIX).append(result.getSelectorValue()).toString();
                
                IAtomicLong counter = getOrCreateCounter(counterName);
                if (counter == null) {
                    return true;
                }
                
                // Verificar e atualizar contador
                if (processCounter(counter, currentTime, timeInterval, timeUnit, rateLimitValue, msg, counterName)) {
                    return false;
                }
                
                return true;
            }
        }
        return true;
    }

    // Classe auxiliar para evitar array
    private static class RateLimitResult {
        private boolean applicable;
        private String selectorValue;
        
        public void update(boolean applicable, String selectorValue) {
            this.applicable = applicable;
            this.selectorValue = selectorValue;
        }
        
        public boolean isApplicable() {
            return applicable;
        }
        
        public String getSelectorValue() {
            return selectorValue;
        }
    }

    // Métodos auxiliares otimizados
    private void checkRateLimitApplicability(Message msg, String rateLimitKeyBD, 
        String rateLimitKeySelector, String userKeySelectorParam, RateLimitResult result) {
        
        Selector<String> keySelector = new Selector<>(rateLimitKeySelector, String.class);
        Selector<String> key = new Selector<>(rateLimitKeyBD, String.class);
        Selector<String> userKeySelector = new Selector<>(userKeySelectorParam, String.class);

        String selectorValue = keySelector.substitute(msg);
        String selectorKey = key.substitute(msg);
        String userKey = userKeySelector.substitute(msg);

        result.update(selectorKey.equals(selectorValue) && selectorKey.equals(userKey), selectorValue);
    }

    private boolean processCounter(IAtomicLong counter, long currentTime, int timeInterval, 
        TimeUnit timeUnit, long rateLimitValue, Message msg, String counterName) {
        
        IMap<String, Long> expirationMap = hazelcastInstance.getMap(RATE_LIMITER_PREFIX + "expiration");
        Long lastResetTime = expirationMap.get(counterName);
        
        if (lastResetTime == null || (currentTime - lastResetTime) >= timeUnit.toMillis(timeInterval)) {
            counter.set(0);
            expirationMap.put(counterName, currentTime, timeInterval, timeUnit);
        }
        
        long currentCount = counter.incrementAndGet();
        boolean isExceeded = currentCount > rateLimitValue;
        
        updateMetrics(msg, counterName, !isExceeded, rateLimitValue - currentCount);
        
        return isExceeded;
    }

    private void updateMetrics(Message msg, String counterName, boolean isValid, long remain) {
        msg.put(counterName + ".is-exceeded", !isValid);
        msg.put(counterName + ".remain", isValid ? remain : 0);
    }

    private long parseLongSafely(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (Exception e) {
            return 0L;
        }
    }

    private int parseIntSafely(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (Exception e) {
            return 0;
        }
    }


	public boolean validateRateLimitUsingMap(Message msg, String userKeySelectorParam) {
		StringBuilder sb = new StringBuilder(128);
		List<Map<String, Object>> rateLimits = getRateLimit(msg);

		if (Trace.isDebugEnabled()) {
			sb.setLength(0);
			sb.append("Rate limits: ").append(rateLimits);
			Trace.debug(sb.toString());
		}

		if (rateLimits.isEmpty()) {
			return true;
		}

		long currentTime = System.currentTimeMillis();

		for (Map<String, Object> rateLimit : rateLimits) {
			String rateLimitKeyBD = (String) rateLimit.get("rateLimitkey");
			String rateLimitKeySelector = (String) rateLimit.get("rateLimitkeySelector");

			Selector<String> keySelector = new Selector<>(rateLimitKeySelector, String.class);
			Selector<String> key = new Selector<>(rateLimitKeyBD, String.class);
			Selector<String> userKeySelector = new Selector<>(userKeySelectorParam, String.class);

			String selectorValue = keySelector.substitute(msg);
			String selectorKey = key.substitute(msg);
			String userKey = userKeySelector.substitute(msg);

			if (selectorKey.equals(selectorValue) && selectorKey.equals(userKey)) {
				long rateLimitValue = parseLongSafely(rateLimit.get("rateLimit"));
				int timeInterval = parseIntSafely(rateLimit.get("timeInterval"));
				TimeUnit timeUnit = mapStringToTimeUnit((String) rateLimit.get("timeUnit"));

				String mapName = selectorValue;
				IMap<String, Long> map = hazelcastInstance.getMap(mapName);

				int rate = validateRateLimitWithTTL(map);
				rate++;

				boolean isValid = rate <= rateLimitValue;

				if (isValid) {
					// Usando a concatenação específica para correlationId
					String identificadorReq = "" + msg.correlationId + "";
					map.put(identificadorReq, currentTime, timeInterval, timeUnit);
				}

				// Construir chaves de métrica
				sb.setLength(0);
				sb.append("rate-limit.").append(mapName);
				String metricBase = sb.toString();

				msg.put(metricBase + ".is-exceeded", !isValid);
				long remain = rateLimitValue - rate;
				msg.put(metricBase + ".remain", !isValid ? 0 : remain);

				return isValid;
			}
		}
		return true;
	}
    private int validateRateLimitWithTTL(IMap<String, Long> map) {
        return map.size();
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