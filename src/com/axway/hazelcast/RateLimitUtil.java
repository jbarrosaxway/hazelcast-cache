package com.axway.hazelcast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import com.hazelcast.map.IMap;
import com.vordel.circuit.Message;
import com.vordel.common.Dictionary;
import com.vordel.el.Selector;
import com.vordel.trace.Trace;

public class RateLimitUtil {
    private static final RateLimitUtil instance = new RateLimitUtil();

    private RateLimitUtil() {}
    
    private HazelcastInstance hazelcastInstance;
    private static boolean isTtlEnabled = true;

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
        String mapName;
        Trace.debug("Rate limits: " + rateLimits.toString());
		long currentTime = System.currentTimeMillis();
        if (rateLimits.isEmpty()) {
            return true;
        } else {
            for (Map<String, Object> rateLimit : rateLimits) {
            	boolean isValid = true;
                String rateLimitKeyBD = (String) rateLimit.get("rateLimitkey");
                Trace.debug("Rate limit key: " + rateLimitKeyBD);
                String rateLimitKeySelector = (String) rateLimit.get("rateLimitkeySelector");
                Trace.debug("Rate limit key selector: " + rateLimitKeySelector);
                
                Selector<String> keySelector = new Selector<String>(rateLimitKeySelector, String.class);
                Selector<String> key = new Selector<String>(rateLimitKeyBD, String.class);
                Selector<String> userKeySelector = new Selector<String>(userKeySelectorParam, String.class);
                String selectorValue = (String) keySelector.substitute(msg);
                Trace.debug("Selector value: " + selectorValue);
                String selectorKey = (String) key.substitute(msg);
                Trace.debug("Selector key: " + selectorKey);
                String userKey = (String) userKeySelector.substitute(msg);
                Trace.debug("Selector user key: " + userKey);
                Trace.debug("Evaluator: "+selectorKey+".equals("+selectorValue+") && "+selectorKey+".equals("+userKey+")");
                if(selectorKey.equals(selectorValue) && selectorKey.equals(userKey)) {
                    Number rateLimitValue = Long.parseLong((String)rateLimit.get("rateLimit"));
                    Trace.debug("Rate limit value: " + rateLimitValue);
                    Integer timeInterval = (Integer) Integer.parseInt((String)rateLimit.get("timeInterval"));
                    Trace.debug("Time interval: " + timeInterval);
                    String timeUnitStr = (String) rateLimit.get("timeUnit");
                    Trace.debug("Time unit: " + timeUnitStr);
                    TimeUnit timeUnit = mapStringToTimeUnit(timeUnitStr);
                	
                	Trace.debug("Rate limit key matches with selector value");
	                mapName = selectorValue;
	                
	                Trace.debug("Map name: " + mapName);
	
	                IMap<String, Long> map = getHazelcastInstance().getMap(mapName);
	                
	                int rate = 0;
					if (isTtlEnabled) {
						rate = validateRateLimitWithTTL(map);
					} else {
						rate = validateRateLimitWithoutTTL(timeInterval, timeUnit, map);
					}
	                
	        		// Atualiza a contagem para incluir a chamada atual
	                rate += 1;
	                
	                Trace.debug("Rate: " + rate);
	                
	
	        		if (rate > rateLimitValue.intValue()) {
	        		    Trace.debug("Rate limit exceeded for " + mapName + " rate: " + rate);
	        		    isValid = false;
	        		} else {
	        		    // Armazena a nova requisição com o timestamp atual
	        			String identificadorReq = ""+msg.correlationId+"";
	        			Trace.debug("Identificador da requisição: " + identificadorReq);
	        			if (isTtlEnabled) {
	        				map.put(identificadorReq, currentTime, timeInterval, timeUnit);
	        			} else {
	        				map.put(identificadorReq, currentTime);
	        			}
	        		    Trace.debug("Rate limit applied for " + mapName + ". Current count: " + rate);
	        		}
	                
	                msg.put("rate-limit."+mapName+".is-exceeded", !isValid);
	                int remain = rateLimitValue.intValue() - rate;
	                msg.put("rate-limit."+mapName+".remain", !isValid?0:remain);
	                break;
	            }else {
					Trace.debug("Rate limit key does not match with selector value");
	            }
            }
            
            return true;
        }
    }

	private int validateRateLimitWithTTL(IMap<String, Long> map) {
		Set<String> keys = map.keySet();
		Trace.debug("Keys: " + keys);
		int rate = keys.size(); // Include current call in rate limit
		return rate;
	}
	
	private int validateRateLimitWithoutTTL(Integer timeInterval,
			TimeUnit timeUnit, IMap<String, Long> map) {
		Set<String> keys = map.keySet();
		Trace.debug("Keys: " + keys);
		// Supondo que 'map' é um IMap<String, Long>, onde o valor é o timestamp da requisição
		long currentTime = System.currentTimeMillis();
		long ttlMillis = TimeUnit.MILLISECONDS.convert(timeInterval, timeUnit);

		// Calcula o limiar de tempo para considerar uma requisição expirada
		long expiredTimeThreshold = currentTime - ttlMillis;
		int validRequestCount = 0;

		// Cria uma lista temporária para armazenar as chaves das requisições expiradas
		List<String> expiredKeys = new ArrayList<>();

		for (String key : keys) {
		    Long requestTime = map.get(key);
		    if (requestTime != null && requestTime > expiredTimeThreshold) {
		        validRequestCount++;
		    } else {
		        // Adiciona chaves expiradas à lista temporária
		        expiredKeys.add(key);
		    }
		}

		// Remove as chaves expiradas do mapa
		for (String expiredKey : expiredKeys) {
		    map.remove(expiredKey);
		}

		return validRequestCount;
	}
    
    
    
}