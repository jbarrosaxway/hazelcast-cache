package com.axway.hazelcast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.axway.apigw.cassandra.api.ClusterConnectionPool;
import com.axway.apigw.cassandra.api.constants.TableEnum;
import com.axway.apigw.cassandra.api.kps.BasicDmlOperations;
import com.axway.apigw.cassandra.factory.CassandraObjectFactory;
import com.datastax.driver.core.BoundStatement;
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
    private static final RateLimitUtil instance = new RateLimitUtil();
    
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private RateLimitUtil() {
        // Agenda a limpeza para executar a cada hora
        scheduler.scheduleAtFixedRate(this::cleanupExpiredCounters, 1, 1, TimeUnit.HOURS);
    	
    }
    
    private HazelcastInstance hazelcastInstance;
    
	// Adicionar esta constante
	private static final String RATE_LIMITER_PREFIX = "rate-limit.";

    public static RateLimitUtil getInstance() {
        return instance;
    }

    private HazelcastInstance getHazelcastInstance() {
        hazelcastInstance = Hazelcast.getHazelcastInstanceByName("axway-instance");
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
    
    private List<Map<String, Object>> getRateLimit(Dictionary msg){
        IMap<String, List<Map<String, Object>>> cache = getHazelcastInstance().getMap("rateLimitCache")  ;  
        String cacheKey = "rateLimits:cassandra";

        // Verificar se o resultado já está no cache
        if (cache.containsKey(cacheKey)) {
            Trace.debug("Cache hit for key: "+cacheKey);
            return cache.get(cacheKey);
        }
        Trace.debug("Cache miss for key: "+cacheKey);

        ClusterConnectionPool cassandraConnectionPool = CassandraObjectFactory.getClusterConnectionPool();
        Session session = cassandraConnectionPool.getSession();
        String keyspace = cassandraConnectionPool.getKeySpace();
        String table = "\"Axway_RateLimit\"";
        table = table.toLowerCase();
        ConsistencyLevel rcLevel = ConsistencyLevel.valueOf(BasicDmlOperations.readConsistencies.getOrDefault(getFullyQualifiedTable(keyspace, table), "ONE"));
        
        String select = "SELECT * FROM "+getFullyQualifiedTable(keyspace, table);
        
        
        
        PreparedStatement ps = session.prepare(select).setConsistencyLevel(rcLevel);
        BoundStatement bound = ps.bind();
        Trace.debug("select: "+select);
        Trace.debug("Prepared statement: "+ps.getQueryString());
            
        ResultSet rs = session.execute((com.datastax.driver.core.Statement) bound);

        Trace.debug("Executed statement, now parsing resultset...");

        if (rs.isExhausted()) {
            Trace.debug("Result set is empty");
            Trace.debug("}");
            return new ArrayList<Map<String,Object>>();
        }
        List<Map<String, Object>> results = new ArrayList<>();
        
        rs.forEach((row) -> {
            Map<String, Object> result = new HashMap<>();
            
            List<ColumnDefinitions.Definition> defsList = row.getColumnDefinitions().asList();
            
            for (ColumnDefinitions.Definition d : defsList) {
                String name = d.getName();
                if (TableEnum.KEY.getValue().equals(name))
                  continue; 
                String value = row.getString("\"" + name + "\"");
                 
                if (value == null)
                  continue;
                if(value.startsWith("\"") && value.endsWith("\"")) {
                	String valueWhitoutQuotes = value.substring(1, value.length() - 1);
                	Trace.debug(name + " : " + valueWhitoutQuotes);
                	value = valueWhitoutQuotes;
                }
                result.put(name, value);
              } 
            Trace.debug("}");        
            results.add((Map<String, Object>) result);
        });
        List<Map<String, Object>> validatedRateLimits = results;
        cache.put(cacheKey, validatedRateLimits);

        return validatedRateLimits;
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
                    Number rateLimitValue = Long.parseLong((String)rateLimit.get("rateLimit"));
                    Integer timeInterval = Integer.parseInt((String)rateLimit.get("timeInterval"));
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
                    IMap<String, Long> expirationMap = getHazelcastInstance().getMap(RATE_LIMITER_PREFIX+"expiration");
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
            // Primeiro, desliga o scheduler
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }

            // Limpa todos os contadores
            IMap<String, Long> expirationMap = getHazelcastInstance().getMap(RATE_LIMITER_PREFIX+"expiration");
            for (String counterName : expirationMap.keySet()) {
                IAtomicLong counter = getHazelcastInstance().getCPSubsystem().getAtomicLong(counterName);
                counter.destroy();
            }
            expirationMap.clear();
            
            // Shutdown da instância do Hazelcast
            if (hazelcastInstance != null) {
                hazelcastInstance.shutdown();
                hazelcastInstance = null;
            }
        } catch (Exception e) {
            Trace.error("Erro ao destruir RateLimitUtil", e);
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
			    
}