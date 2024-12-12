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
import com.hazelcast.map.IMap;
import com.vordel.circuit.Message;
import com.vordel.common.Dictionary;
import com.vordel.el.Selector;
import com.vordel.trace.Trace;

public class RateLimitUtil {
    private static final String RATE_LIMIT_TYPE_MAP = "map";
    private static final String CACHE_MAP_NAME = "rateLimitCache";
    private static final String CACHE_KEY = "rateLimits:cassandra";
    private static final long CACHE_TTL = TimeUnit.MINUTES.toMillis(5); // 5 minutos de TTL
    private static final String RATE_LIMITER_PREFIX = "rate-limit.";
    private static final String EXPIRATION_MAP_NAME = RATE_LIMITER_PREFIX + "expiration";

    // Constantes para configuração dos schedulers
    private static final int INITIAL_DELAY = 1;
    
    // Períodos de limpeza em horas e minutos
    private static final int COUNTER_CLEANUP_PERIOD = 1;
    private static final int CACHE_CLEANUP_PERIOD = 30;

    // Cache para contadores de rate limit
    private final Map<String, RateLimitCounter> counterCache = new ConcurrentHashMap<>();
    
    // Cache para níveis de consistência
    private static final Map<String, ConsistencyLevel> consistencyLevelCache = new ConcurrentHashMap<>();
    
    // Instância singleton
    private static volatile RateLimitUtil instance = null;
    
    // Scheduler para limpeza de contadores
    private final ScheduledExecutorService scheduler;
    
    // Instância do Hazelcast
    private volatile HazelcastInstance hazelcastInstance;
    
    // Cache local para reduzir acessos ao Hazelcast
    private volatile List<Map<String, Object>> localRateLimitCache;
    
    // Timestamp do último update do cache local
    private volatile long lastCacheUpdate;
    
    // ThreadLocal para evitar vazamentos de memória
    private static final ThreadLocal<Map<String, Object>> threadLocalCache = 
        ThreadLocal.withInitial(HashMap::new);
    
    // Cache para PreparedStatements
    private final Map<String, PreparedStatement> preparedStatementCache = new ConcurrentHashMap<>();
    
    // Tipo de rate limit (map ou atomic)
	private String rateLimitType;
    
	// Construtor privado para inicialização do RateLimitUtil
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

    // Construtor privado para inicialização do RateLimitUtil com instância Hazelcast
    private RateLimitUtil(HazelcastInstance hazelcastInstance, String rateLimitType) {
        this.hazelcastInstance = hazelcastInstance;
        this.scheduler = null;
        this.rateLimitType = rateLimitType;
                
        Trace.info("RateLimitUtil iniciado com instância Hazelcast fornecida. Tipo de rate limit: " + rateLimitType);
    }

    // Método para inicializar a instância singleton
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

    // Método para obter a instância singleton
	public static RateLimitUtil getInstance() {
		return instance;
	}

	// Método para obter a instância do Hazelcast
    private HazelcastInstance getHazelcastInstance() {
        // Não precisamos mais procurar a instância, pois ela já foi injetada
        if (hazelcastInstance == null) {
            Trace.error("Não foi possível encontrar a instância do Hazelcast.");
        }
        return hazelcastInstance;
    }
    
    // Método para realizar as validações de rate limit
    public boolean performRateLimitValidations(Message msg, String userKeySelectorParam) {
    	return validateRateLimit(msg, userKeySelectorParam);
    }

    // Método para mapear uma string para TimeUnit
    private TimeUnit mapStringToTimeUnit(String timeUnitStr) {
        try {
            return TimeUnit.valueOf(timeUnitStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid TimeUnit string: " + timeUnitStr);
        }
    }
    
    // Método para obter a tabela completamente qualificada
    private String getFullyQualifiedTable(String keySpace, String table) {
        return keySpace + "." + table;
    }
    
    // Método para obter os rate limits do cache ou do Cassandra
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
    
    // Método para buscar e armazenar os rate limits no cache
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

    // Método para buscar os rate limits do Cassandra
    private List<Map<String, Object>> fetchRateLimitsFromCassandra() {
        List<Map<String, Object>> results = new ArrayList<>();
        ClusterConnectionPool connectionPool = null;
        
        try {
            connectionPool = CassandraObjectFactory.getClusterConnectionPool();
            final Session session = connectionPool.getSession();
            String keyspace = connectionPool.getKeySpace();
            // Tabela específica para rate limit
            String table = "\"Axway_RateLimit\"".toLowerCase();
            
            // Consulta para buscar todos os rate limits
            String select = "SELECT * FROM " + getFullyQualifiedTable(keyspace, table);
            
            // Usar cache de PreparedStatement
            PreparedStatement ps = preparedStatementCache.computeIfAbsent(select, 
                k -> session.prepare(k).setConsistencyLevel(getConsistencyLevel(keyspace, table)));
                
            ResultSet rs = session.execute(ps.bind());
            
            // Processar o ResultSet
            if (!rs.isExhausted()) {
                processResultSet(rs, results);
            }
            
        } catch (Exception e) {
            Trace.error("Erro ao buscar dados do Cassandra", e);
        }
        
        return results;
    }
    
    // Método para processar o ResultSet do Cassandra
    private void processResultSet(ResultSet rs, List<Map<String, Object>> results) {
        ColumnDefinitions columnDefs = rs.getColumnDefinitions();
        
        for (Row row : rs) {
            Map<String, Object> result = new HashMap<>();
            
            // Iterar sobre as colunas e adicionar ao resultado
            for (ColumnDefinitions.Definition def : columnDefs) {
                String name = def.getName();
                // Ignorar a coluna KEY
                if (!TableEnum.KEY.getValue().equals(name)) {

                    String quotedName = "\"" + name + "\"";
                    String value = row.getString(quotedName);
                	// Adicionar ao resultado se não for nulo
                    if (value != null) {
                    	// Remover aspas do valor
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
        	// Limpar o cache da ThreadLocal
            Map<String, Object> cache = threadLocalCache.get();
            if (cache != null) {
                cache.clear();
            }
            threadLocalCache.remove();
        } catch (Exception e) {
            Trace.error("Erro ao limpar ThreadLocal: " + e.getMessage());
        }
    }

    // Método para validar o rate limit usando AtomicLong ou Map
    public boolean validateRateLimit(Message msg, String userKeySelectorParam) {
        // Usar a variável de instância em vez de tentar obter da configuração
        if (RATE_LIMIT_TYPE_MAP.equalsIgnoreCase(this.rateLimitType)) {
            return validateRateLimitUsingMap(msg, userKeySelectorParam);
        } else {
            return validateRateLimitUsingAtomic(msg, userKeySelectorParam);
        }
    }


    // Método para validar o rate limit usando AtomicLong
    public boolean validateRateLimitUsingAtomic(Message msg, String userKeySelectorParam) {
        List<Map<String, Object>> rateLimits = getRateLimit(msg);
        
        if (rateLimits.isEmpty()) {
            return true;
        }
        // Usar o tempo atual em milissegundos
        long currentTime = System.currentTimeMillis();
        RateLimitResult result = new RateLimitResult();
        
        // Iterar sobre os rate limits e process
        for (Map<String, Object> rateLimit : rateLimits) {
            if (isRateLimitApplicable(msg, rateLimit, userKeySelectorParam, result)) {
            	// Nome do contador com prefixo
                String counterName = RATE_LIMITER_PREFIX + result.getSelectorValue();
                // Obter ou criar o contador
                RateLimitCounter counter = getOrCreateCounter(counterName);
                
                // Obter os valores de rate limit e intervalo de tempo
                long rateLimitValue = parseLongSafely(rateLimit.get("rateLimit"));
                int timeInterval = parseIntSafely(rateLimit.get("timeInterval"));
                TimeUnit timeUnit = mapStringToTimeUnit((String) rateLimit.get("timeUnit"));
                
                // Processar o contador
                if (processCounter(counter, currentTime, timeInterval, timeUnit, rateLimitValue, msg)) {
                	// Rate limit excedido
                    return false;
                }
            }
        }
        // Rate limit não excedido
        return true;
    }

    // Método para verificar se o rate limit é aplicável
    private boolean isRateLimitApplicable(Message msg, Map<String, Object> rateLimit, 
    	    String userKeySelectorParam, RateLimitResult result) {
    	    
    		// Obter as chaves de rate limit e usuário
    	    String rateLimitKeyBD = (String) rateLimit.get("rateLimitkey");
    	    String rateLimitKeySelector = (String) rateLimit.get("rateLimitkeySelector");
    	    
    	    // Substituir as chaves de rate limit e usuário
    	    Selector<String> keySelector = new Selector<>(rateLimitKeySelector, String.class);
    	    Selector<String> key = new Selector<>(rateLimitKeyBD, String.class);
    	    Selector<String> userKeySelector = new Selector<>(userKeySelectorParam, String.class);
    	    
    	    String selectorValue = keySelector.substitute(msg);
    	    String selectorKey = key.substitute(msg);
    	    String userKey = userKeySelector.substitute(msg);
    	    
    	    // Verificar se as chaves são iguais
    	    boolean applicable = selectorKey.equals(selectorValue) && selectorKey.equals(userKey);
    	    // Atualizar o resultado com a aplicabilidade e o valor do seletor
    	    result.update(applicable, selectorValue);
    	    
    	    // Retornar a aplicabilidade
    	    return applicable;
    	}
    
    // Classe interna para armazenar o resultado da verificação de rate limit
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

    // Método para processar o contador de rate limit
    private boolean processCounter(RateLimitCounter counter, long currentTime, 
            int timeInterval, TimeUnit timeUnit, long rateLimitValue, Message msg) {
        
        final IMap<String, Long> expirationMap = hazelcastInstance.getMap(EXPIRATION_MAP_NAME);
        final String counterName = counter.getName();
        
        try {
            // Verifica expiração com uma única operação de mapa
            Long lastResetTime = expirationMap.get(counterName);
            long expirationTime = timeUnit.toMillis(timeInterval);
            
            // Verifica se o contador precisa ser resetado
            boolean needsReset = lastResetTime == null || (currentTime - lastResetTime) >= expirationTime;
            
            if (needsReset) {
                synchronized (counter) {  // Sincronização no nível do contador
                    // Dupla verificação para evitar resets desnecessários
                    lastResetTime = expirationMap.get(counterName);
                    if (lastResetTime == null || (currentTime - lastResetTime) >= expirationTime) {
                        counter.reset();
                        expirationMap.set(counterName, currentTime, timeInterval, timeUnit);
                    }
                }
            }
            
            // Tenta incrementar com retry
            long currentCount = counter.incrementAndGet(1);
            boolean isExceeded = currentCount > rateLimitValue;
            // Atualiza as métricas
            updateMetrics(msg, counterName, !isExceeded, 
                isExceeded ? 0 : rateLimitValue - currentCount);
            
            // Retorna se o rate limit foi excedido
            return isExceeded;
            
        } catch (Exception e) {
            Trace.error("Erro ao processar contador: " + counterName, e);
            // Em caso de erro, permitimos a requisição passar
            return false;
        }
    }

    // Método para limpar um contador
    private void cleanupCounter(String counterName) {
        try {
        	// Remover o contador do cache e do mapa de expiração
            RateLimitCounter counter = counterCache.get(counterName);
            if (counter != null) {
                synchronized (counter) {
                    counter = counterCache.remove(counterName);
                    if (counter != null) {
                        counter.destroy();
                    }
                }
            }
            // Remover do mapa de expiração
            final IMap<String, Long> expirationMap = hazelcastInstance.getMap(EXPIRATION_MAP_NAME);
            expirationMap.delete(counterName);
            
            if (Trace.isDebugEnabled()) {
                Trace.debug("Contador removido com sucesso: " + counterName);
            }
        } catch (Exception e) {
            Trace.error("Erro ao limpar contador: " + counterName, e);
        }
    }

    // Método para atualizar as métricas
    private void updateMetrics(Message msg, String counterName, boolean isValid, long remain) {
    	// Construir chaves de métrica
        StringBuilder sb = new StringBuilder(counterName.length() + 12);
        
        sb.append(counterName).append(".is-exceeded");
        msg.put(sb.toString(), !isValid);
        
        sb.setLength(0);
        sb.append(counterName).append(".remain");
        msg.put(sb.toString(), remain);
    }


    // Método para converter um objeto em long com segurança evitando uso de memória pois não é necessário criar um novo objeto Number para fazer a conversão para long
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

    // Método para converter um objeto em int com segurança evitando uso de memória pois não é necessário criar um novo objeto Number para fazer a conversão para int
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

    // Método para validar o rate limit usando
    public boolean validateRateLimitUsingMap(Message msg, String userKeySelectorParam) {
        StringBuilder sb = new StringBuilder(128);
        List<Map<String, Object>> rateLimits = getRateLimit(msg);
        
        // Debug dos rate limits
        if (Trace.isDebugEnabled()) {
            sb.setLength(0);
            sb.append("Rate limits: ").append(rateLimits);
            Trace.debug(sb.toString());
        }

        if (rateLimits.isEmpty()) {
            return true;
        }

        // Usando o tempo atual em milissegundos
        long currentTime = System.currentTimeMillis();
        RateLimitResult result = new RateLimitResult();

        // Iterar sobre os rate limits e processar
        for (Map<String, Object> rateLimit : rateLimits) {
            if (isRateLimitApplicable(msg, rateLimit, userKeySelectorParam, result)) {
                long rateLimitValue = parseLongSafely(rateLimit.get("rateLimit"));
                int timeInterval = parseIntSafely(rateLimit.get("timeInterval"));
                TimeUnit timeUnit = mapStringToTimeUnit((String) rateLimit.get("timeUnit"));

                String mapName = result.getSelectorValue();
                IMap<String, Long> map = hazelcastInstance.getMap(mapName);

                int rate = validateRateLimitWithTTL(map);
                rate++;

                boolean isValid = rate <= rateLimitValue;

                if (isValid) {
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

    // Método para validar o rate limit com TTL (usado somente no método validateRateLimitUsingMap)
    private int validateRateLimitWithTTL(IMap<String, Long> map) {
        return map.size();
    }
    
    // Método para limpar contadores expirados
    private void cleanupExpiredCounters() {
    	// Mapa de expiração para limpar contadores
        final IMap<String, Long> expirationMap = hazelcastInstance.getMap(EXPIRATION_MAP_NAME);
        final long currentTime = System.currentTimeMillis();
        
        // Usa entrySet para reduzir operações de rede
        for (Map.Entry<String, Long> entry : expirationMap.entrySet()) {
            try {
                String counterName = entry.getKey();
                Long lastAccess = entry.getValue();
                // Limpa contadores expirados após 1 hora
                if (lastAccess != null && (currentTime - lastAccess) >= TimeUnit.HOURS.toMillis(1)) {
                    cleanupCounter(counterName);
                }
            } catch (Exception e) {
                Trace.error("Erro durante limpeza de contadores expirados", e);
            }
        }
    }

    // Método para destruir o RateLimitUtil
    public void destroy() {
        try {
            if (Trace.isDebugEnabled()) {
                Trace.info("Iniciando limpeza de recursos locais do RateLimitUtil...");
            }
        
            // Apenas desliga o scheduler local
            shutdownScheduler();
            
            // Limpa apenas recursos locais
            clearLocalResources();
            
            if (Trace.isDebugEnabled()) {
                Trace.info("Recursos locais do RateLimitUtil limpos com sucesso.");
            }
            
        } catch (Exception e) {
            Trace.error("Erro durante limpeza de recursos locais do RateLimitUtil", e);
            throw new RuntimeException("Falha ao limpar recursos locais do RateLimitUtil", e);
        }
    }
    // Método para desligar o scheduler
    private void shutdownScheduler() {
        if (scheduler != null) {
            try {
                scheduler.shutdown();
                // Aguardar até 5 segundos para desligar
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Trace.error("Interrompido durante shutdown do scheduler", e);
            } catch (Exception e) {
                Trace.error("Erro ao desligar scheduler", e);
            }
        }
    }

    // Método para limpar recursos locais
    private void clearLocalResources() {
        // Limpa apenas o cache local de PreparedStatements
        synchronized (preparedStatementCache) {
            preparedStatementCache.clear();
        }
        
        // Limpa referências locais
        localRateLimitCache = null;
        lastCacheUpdate = 0;
        
        // Fecha conexões locais do Cassandra usando método existente
        closeCassandraResources();
    }

    // Método para fechar recursos do Cassandra
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

    // Método para obter ou criar um contador
    private RateLimitCounter getOrCreateCounter(String counterName) {
        return counterCache.computeIfAbsent(counterName, 
            k -> new RateLimitCounter(hazelcastInstance, k));
    }

    // Método para obter o nível de consistência do Cassandra, como fallback, deve considerar node unico, ou seja, LOCAL_ONE
    private ConsistencyLevel getConsistencyLevel(String keyspace, String table) {
        String cacheKey = keyspace + "." + table;
        return consistencyLevelCache.computeIfAbsent(cacheKey, k -> {
            try {
                String tableSpecificLevel = System.getProperty(
                    String.format("cassandra.consistency.%s.%s", keyspace, table),
                    System.getProperty("cassandra.consistency.default", "")
                );
                return ConsistencyLevel.valueOf(tableSpecificLevel.toUpperCase());
            // Em caso de erro, retorna LOCAL_ONE como fallback
            } catch (IllegalArgumentException e) {
                Trace.info("Nível de consistência inválido configurado, usando LOCAL_ONE como fallback");
                return ConsistencyLevel.LOCAL_ONE;
            }
        });
    }

    //  Método para limpar o cache de Prepared
    private void cleanupPreparedStatementCache() {
        synchronized (preparedStatementCache) {
            if (Trace.isDebugEnabled()) {
                try {
                	// Limpar o cache de PreparedStatements e exibir informações de depuração
                    int sizeBefore = preparedStatementCache.size();
                    preparedStatementCache.clear();
                    Trace.debug("Cache de PreparedStatements limpo. Tamanho anterior: " + sizeBefore);
                } catch (Exception e) {
                    Trace.error("Erro durante limpeza do cache de PreparedStatements", e);
                }
            } else {
            	// Limpar o cache de PreparedStatements
                preparedStatementCache.clear();
            }
        }
    }


			    
}