package com.axway.hazelcast;

import com.vordel.config.LoadableModule;
import com.vordel.config.ConfigContext;
import com.vordel.es.EntityStoreException;
import com.vordel.trace.Trace;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;


import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.config.KubernetesConfig;
import com.hazelcast.config.TcpIpConfig;


public class HazelcastCacheManager implements LoadableModule {

    private HazelcastInstance hazelcastInstance;

    @Override
    public void configure(ConfigContext ctx, com.vordel.es.Entity entity)
            throws EntityStoreException {
    }

    @Override
    public void load(LoadableModule parent, String typeName) {
    	
        String propFilePath = "conf/envSettings.props";
        Properties props = loadProperties(propFilePath);
    	
        String instanceName = props.getProperty("env.HAZELCAST.instanceName", "axway-instance");
        
        String port = props.getProperty("env.HAZELCAST.port", "5701");
        
        
    	hazelcastInstance = Hazelcast.getHazelcastInstanceByName(instanceName);
    	if (hazelcastInstance != null) {
    		hazelcastInstance.shutdown();
    		hazelcastInstance = null;
    	}

        
        Config config = new Config();
        config.getNetworkConfig().setPort(Integer.parseInt(port));
        
                
        // Configurações de diagnóstico
        // Habilitar o diagnóstico
        config.setProperty("hazelcast.diagnostics.enabled", props.getProperty("env.HAZELCAST.diagnostics.enabled", "false"));

        // Configurar o nível de métrica do diagnóstico
        config.setProperty("hazelcast.diagnostics.metric.level", props.getProperty("env.HAZELCAST.diagnostics.metric.level", "info"));

        // Configurar o período de amostragem de invocações
        config.setProperty("hazelcast.diagnostics.invocation.sample.period.seconds", props.getProperty("env.HAZELCAST.diagnostics.invocation.sample.period.seconds", "30"));

        // Configurar o período de invocações pendentes
        config.setProperty("hazelcast.diagnostics.pending.invocations.period.seconds", props.getProperty("env.HAZELCAST.diagnostics.pending.invocations.period.seconds", "30"));

        // Configurar o período de operações lentas
        config.setProperty("hazelcast.diagnostics.slowoperations.period.seconds", props.getProperty("env.HAZELCAST.diagnostics.slowoperations.period.seconds", "30"));

        // Configurar o diretório dos logs de diagnóstico
        config.setProperty("hazelcast.diagnostics.directory", props.getProperty("env.HAZELCAST.diagnostics.directory", "logs"));

        // Configurar o tamanho máximo do arquivo de log de diagnóstico
        config.setProperty("hazelcast.diagnostics.max.rolled.file.size.mb", props.getProperty("env.HAZELCAST.diagnostics.max.rolled.file.size.mb", "50"));

        // Configurar o número máximo de arquivos de log de diagnóstico
        config.setProperty("hazelcast.diagnostics.max.rolled.file.count", props.getProperty("env.HAZELCAST.diagnostics.max.rolled.file.count", "10"));

        // Configurar o prefixo do nome do arquivo de diagnóstico
        config.setProperty("hazelcast.diagnostics.filename.prefix", props.getProperty("env.HAZELCAST.diagnostics.filename.prefix", "diagnostics"));
        
        // Configurações de log
        config.setProperty("hazelcast.logging.level", props.getProperty("env.HAZELCAST.logging.level", "INFO"));

        // Configurações de expiração de mapas para rate limiting
        config.setProperty("hazelcast.internal.map.expiration.task.period.seconds", props.getProperty("env.HAZELCAST.internal.map.expiration.task.period.seconds", "1"));
        config.setProperty("hazelcast.internal.map.expiration.cleanup.percentage", props.getProperty("env.HAZELCAST.internal.map.expiration.cleanup.percentage", "100"));

        // Otimizações específicas para rate limiting
        config.setProperty("hazelcast.map.write.batch.size", props.getProperty("env.HAZELCAST.map.write.batch.size", "100"));
        config.setProperty("hazelcast.map.eviction.batch.size", props.getProperty("env.HAZELCAST.map.eviction.batch.size", "100"));
        config.setProperty("hazelcast.map.load.chunk.size", props.getProperty("env.HAZELCAST.map.load.chunk.size", "100"));

        // Desabilitar Near Cache para mapas de rate limiting
        config.setProperty("hazelcast.map.near.cache.enabled", props.getProperty("env.HAZELCAST.map.near.cache.enabled", "true"));

        // Configurações de rede
        config.setProperty("hazelcast.socket.receive.buffer.size", props.getProperty("env.HAZELCAST.socket.receive.buffer.size", "256"));
        config.setProperty("hazelcast.socket.send.buffer.size", props.getProperty("env.HAZELCAST.socket.send.buffer.size", "256"));

        // Otimizações de desempenho
        config.setProperty("hazelcast.operation.thread.count", props.getProperty("env.HAZELCAST.operation.thread.count", String.valueOf(Runtime.getRuntime().availableProcessors())));
        config.setProperty("hazelcast.io.thread.count", props.getProperty("env.HAZELCAST.io.thread.count", "3"));

        // Configurações adicionais para otimização de rate limiting
        config.setProperty("hazelcast.map.eviction.max.entries", props.getProperty("env.HAZELCAST.map.eviction.max.entries", "100000"));
        config.setProperty("hazelcast.map.eviction.max.size", props.getProperty("env.HAZELCAST.map.eviction.max.size", "100MB"));

        // Desabilitar coleta de métricas via JMX
        config.setProperty("hazelcast.metrics.jmx.enabled", props.getProperty("env.HAZELCAST.metrics.jmx.enabled", "false"));

        // Configurações de backup
        // Defina o número de backups para 0 para desativar backups
        config.setProperty("hazelcast.map.backup.count", props.getProperty("env.HAZELCAST.map.backup.count", "0"));
        config.setProperty("hazelcast.map.async.backup.count", props.getProperty("env.HAZELCAST.map.async.backup.count", "0"));
  
        // Configuração de timeout para operações
        config.setProperty("hazelcast.operation.call.timeout.millis", props.getProperty("env.HAZELCAST.operation.call.timeout.millis", "120000"));

        // Configuração de timeout para replicação WAN
        config.setProperty("hazelcast.wan.replication.response.timeout.millis", props.getProperty("env.HAZELCAST.wan.replication.response.timeout.millis", "60000"));

      
        config.setInstanceName(instanceName);
        
        configureDiscovery(config, props);

        hazelcastInstance = Hazelcast.newHazelcastInstance(config);
        Trace.info("Hazelcast Instance iniciado.");


    }

    @Override
    public void unload() {
        if (hazelcastInstance != null) {
            hazelcastInstance.shutdown();
            Trace.info("Hazelcast Instance parado.");
            hazelcastInstance = null;
        } else {
            Trace.info("Hazelcast Instance não está em execução.");
        }
    }

    private static Properties loadProperties(String filePath) {
        Properties props = new Properties();
        try (FileInputStream input = new FileInputStream(filePath)) {
            props.load(input);
        } catch (IOException ex) {
            ex.printStackTrace();
            Trace.error("Erro ao carregar arquivo de propriedades: " + filePath);
        }
        return props;
    }

    private static void configureDiscovery(Config config, Properties props) {
    	Trace.info("Configurando descoberta de membros...");
        NetworkConfig networkConfig = config.getNetworkConfig();
        JoinConfig joinConfig = networkConfig.getJoin();

        joinConfig.getMulticastConfig().setEnabled(false);

        TcpIpConfig tcpIpConfig = joinConfig.getTcpIpConfig();

        String ipList = props.getProperty("env.HAZELCAST.member.ips");
        if (ipList != null && !ipList.isEmpty()) {
        	Trace.info("Configurando descoberta de membros via TCP/IP...");
            for (String ip : ipList.split(",")) {
                tcpIpConfig.addMember(ip.trim());
            }
            tcpIpConfig.setEnabled(true);
        } else {
        	Trace.info("Configurando descoberta de membros via Kubernetes...");
            // Configuração para descoberta via Kubernetes
            KubernetesConfig kubernetesConfig = joinConfig.getKubernetesConfig();
            kubernetesConfig.setEnabled(true);

            // Configurações específicas para o Kubernetes
            String namespace = props.getProperty("env.HAZELCAST.namespace", "axway");
            String serviceName = props.getProperty("env.HAZELCAST.serviceName", "traffic");
            String servicePort = props.getProperty("env.HAZELCAST.servicePort", "5701");
            
            kubernetesConfig.setProperty("namespace", namespace);
            kubernetesConfig.setProperty("service-name", serviceName);
            kubernetesConfig.setProperty("service-port", servicePort);
        }
    }

}