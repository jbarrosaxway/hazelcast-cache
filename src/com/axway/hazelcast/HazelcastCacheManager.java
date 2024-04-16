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
        
        config.setProperty("hazelcast.diagnostics.enabled", props.getProperty("env.HAZELCAST.diagnostics.enabled", "false"));
        config.setProperty("hazelcast.logging.type", props.getProperty("env.HAZELCAST.logging.type", "slf4j"));
        config.setProperty("hazelcast.diagnostics.max.rolled.file.count", props.getProperty("env.HAZELCAST.diagnostics.max.rolled.file.count", "10"));
        config.setProperty("hazelcast.diagnostics.max.rolled.file.size.mb", props.getProperty("env.HAZELCAST.diagnostics.max.rolled.file.size.mb", "10"));
        config.setProperty("hazelcast.diagnostics.directory", props.getProperty("env.HAZELCAST.diagnostics.directory", "logs"));
        
        config.setProperty("hazelcast.internal.map.expiration.task.period.seconds", "1");
        config.setProperty("hazelcast.internal.map.expiration.cleanup.percentage", "100");
        
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