package com.axway.hazelcast;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

import com.vordel.config.ConfigContext;
import com.vordel.config.LoadableModule;
import com.vordel.es.EntityStoreException;
import com.vordel.trace.Trace;

public class HazelcastCacheManager implements LoadableModule {
    //private volatile HazelcastInstance hazelcastInstance;
    private static final String DEFAULT_INSTANCE_NAME = "axway-instance";
    private static final String DEFAULT_PORT = "5701";
    
    // Constantes para otimização
    private static final int DEFAULT_MEMORY_SIZE_MB = 512;
    private static final int DEFAULT_OPERATION_THREAD_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int DEFAULT_IO_THREAD_COUNT = 3;
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 10;
    
    // Constantes para configuração de rede
    private static final int DEFAULT_SOCKET_BUFFER_SIZE = 32768; // 32KB
    private static final int DEFAULT_CONNECTION_MONITOR_INTERVAL = 5;
    private static final int DEFAULT_CONNECTION_MAX_FAULTS = 3;
    

    @Override
    public void configure(ConfigContext ctx, com.vordel.es.Entity entity) throws EntityStoreException {
    }

    private Properties loadProperties(String filePath) {
        Properties props = new Properties();
        try (FileInputStream input = new FileInputStream(filePath)) {
            props.load(input);
            Trace.info("Propriedades carregadas com sucesso de: " + filePath);
        } catch (IOException ex) {
            Trace.error("Erro ao carregar arquivo de propriedades: " + filePath, ex);
        }
        return props;
    }

    @Override
    public void load(LoadableModule parent, String typeName) {
        try {
            String propFilePath = "conf/envSettings.props";
            Properties props = loadProperties(propFilePath); 
            
            if (props == null) {
                throw new IllegalStateException("Não foi possível carregar as propriedades do arquivo: " + propFilePath);
            }
            
            String instanceName = props.getProperty("env.HAZELCAST.instanceName", DEFAULT_INSTANCE_NAME);
            String port = props.getProperty("env.HAZELCAST.port", DEFAULT_PORT);
            
            //shutdownExistingInstance(instanceName);
    
            //Config config = createOptimizedConfig(props, port, instanceName);
            
            //hazelcastInstance = Hazelcast.newHazelcastInstance(config);
            //addClusterMembershipListener(hazelcastInstance);
            //monitorClusterMembers(hazelcastInstance);
            //schedulePeriodicCleanup(); // Adicionar limpeza periódica 
            
            Trace.info("Hazelcast Instance iniciado com configurações otimizadas.");
            
        } catch (Exception e) {
            Trace.error("Erro ao inicializar Hazelcast", e);
            throw new RuntimeException("Falha na inicialização do Hazelcast", e);
        }
    }

    /*private void addClusterMembershipListener(HazelcastInstance hazelcastInstance) {
        if (hazelcastInstance != null) {
            hazelcastInstance.getCluster().addMembershipListener(
                new ClusterMembershipListener(hazelcastInstance)
            );
        }
    }
    private void shutdownExistingInstance(String instanceName) {
        HazelcastInstance existingInstance = Hazelcast.getHazelcastInstanceByName(instanceName);
        if (existingInstance != null) {
            try {
                existingInstance.shutdown();
                if (!existingInstance.getLifecycleService().isRunning()) {
                    Trace.info("Instância existente do Hazelcast desligada com sucesso.");
                }
            } catch (Exception e) {
                Trace.error("Erro ao desligar instância existente do Hazelcast", e);
            } finally {
                existingInstance = null;
            }
        }
    }*/
    /*
    private Config createOptimizedConfig(Properties props, String port, String instanceName) {
        Config config = new Config();
        config.setInstanceName(instanceName);
        //configureSystemResources(config, props);

        // Configurações originais
        //configureCPSubsystem(config, props);
        //configureBasicSettings(config, port);
        //configureDiagnostics(config, props);
        //configureLoggingAndMapSettings(config, props);
        //configureNetworkSettings(config, props);
        //configurePerformanceSettings(config, props);
        //configureTimeoutSettings(config, props);
        //configureDiscovery(config, props);

        // Otimizações adicionais
        //configureMemoryOptimizations(config, props);
        //configureNetworkOptimizations(config, props);
        //configureOperationOptimizations(config, props);
        
        return config;
    }*/
    /*
    private void configureMemoryOptimizations(Config config, Properties props) {
        // Configurações de memória mais agressivas
        config.setProperty("hazelcast.memory.free.min.percentage", 
            props.getProperty("env.HAZELCAST.memory.free.min.percentage", "20"));
        config.setProperty("hazelcast.memory.free.max.percentage", 
            props.getProperty("env.HAZELCAST.memory.free.max.percentage", "30"));
        
        // Configurar GC mais frequente
        config.setProperty("hazelcast.gc.check.period.seconds", 
            props.getProperty("env.HAZELCAST.gc.check.period.seconds", "30"));
        
        // Limitar uso de heap
        int heapSizeMB = Integer.parseInt(
            props.getProperty("env.HAZELCAST.heap.size.mb", String.valueOf(DEFAULT_MEMORY_SIZE_MB)));
        config.setProperty("hazelcast.max.heap.size.mb", String.valueOf(heapSizeMB));
    }

    // Modificar o método schedulePeriodicCleanup
    private void schedulePeriodicCleanup() {
        if (hazelcastInstance != null) {
            Timer cleanupTimer = new Timer("HazelcastCleanupTimer", true);
            cleanupTimer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    try {
                        // Monitorar recursos do sistema
                        monitorSystemResources();
                        
                        // Verificar uso de CPU e memória
                        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
                        double systemLoad = osBean.getSystemLoadAverage();
                        
                        if (systemLoad > 2.0) {
                            Trace.info("System load muito alto (" + systemLoad + "). Forçando limpeza de recursos.");
                            forceCleanupResources();
                            return;
                        }

                        //forceMapCleanup();
                        logDetailedMemoryStatus();
                        
                    } catch (Exception e) {
                        Trace.error("Erro durante limpeza periódica", e);
                    }
                }
            }, 30000, 60000); // Executar a cada 1 minuto, começando após 30 segundos
        }
    }
    
    private void forceMapCleanup() {
        if (hazelcastInstance != null) {
            for (String mapName : hazelcastInstance.getDistributedObjects()
                    .stream()
                    .filter(obj -> obj instanceof IMap)
                    .map(obj -> obj.getName())
                    .collect(Collectors.toList())) {
                
                IMap<?, ?> map = hazelcastInstance.getMap(mapName);
                if (map != null) {
                    map.evictAll();
                    Trace.info("Forçada limpeza do map: " + mapName);
                }
            }
        }
    }

    private void configureNetworkOptimizations(Config config, Properties props) {
        // Usando DEFAULT_SOCKET_BUFFER_SIZE
        config.setProperty("hazelcast.socket.receive.buffer.size", 
            props.getProperty("env.HAZELCAST.socket.buffer.size", String.valueOf(DEFAULT_SOCKET_BUFFER_SIZE)));
        config.setProperty("hazelcast.socket.send.buffer.size", 
            props.getProperty("env.HAZELCAST.socket.buffer.size", String.valueOf(DEFAULT_SOCKET_BUFFER_SIZE)));
    
        // Usando DEFAULT_CONNECTION_MONITOR_INTERVAL e DEFAULT_CONNECTION_MAX_FAULTS
        config.setProperty("hazelcast.connection.monitor.interval", 
            props.getProperty("env.HAZELCAST.connection.monitor.interval", String.valueOf(DEFAULT_CONNECTION_MONITOR_INTERVAL)));
        config.setProperty("hazelcast.connection.monitor.max.faults", 
            props.getProperty("env.HAZELCAST.connection.monitor.max.faults", String.valueOf(DEFAULT_CONNECTION_MAX_FAULTS)));
        
        // Configurações de keep-alive
        config.setProperty("hazelcast.socket.keep.alive", "true");
        config.setProperty("hazelcast.socket.no.delay", "true");
    }

    private void configureOperationOptimizations(Config config, Properties props) {
        // Otimizações de threads
        config.setProperty("hazelcast.operation.thread.count", 
            props.getProperty("env.HAZELCAST.operation.thread.count", 
                String.valueOf(DEFAULT_OPERATION_THREAD_COUNT)));
                
        config.setProperty("hazelcast.io.thread.count", 
            props.getProperty("env.HAZELCAST.io.thread.count", 
                String.valueOf(DEFAULT_IO_THREAD_COUNT)));
                
        // Otimizações de operações
        config.setProperty("hazelcast.operation.generic.thread.count", 
            String.valueOf(DEFAULT_OPERATION_THREAD_COUNT));
            
        config.setProperty("hazelcast.operation.priority.generic.thread.count", 
            String.valueOf(DEFAULT_OPERATION_THREAD_COUNT / 2));
            
        // Cache de operações
        config.setProperty("hazelcast.operation.response.thread.count", 
            String.valueOf(DEFAULT_OPERATION_THREAD_COUNT));
    }
	*/

        // Classe interna para o MembershipListener
    /*private class ClusterMembershipListener implements MembershipListener {
        private final HazelcastInstance hazelcastInstance;

        public ClusterMembershipListener(HazelcastInstance hazelcastInstance) {
            this.hazelcastInstance = hazelcastInstance;
        }

        @Override
        public void memberAdded(MembershipEvent membershipEvent) {
            Trace.info("Membro adicionado ao cluster: " + membershipEvent.getMember().getAddress());
            monitorClusterMembers(hazelcastInstance);
        }

        @Override
        public void memberRemoved(MembershipEvent membershipEvent) {
            Trace.info("Membro removido do cluster: " + membershipEvent.getMember().getAddress());
            monitorClusterMembers(hazelcastInstance);
        }
    }*/


    @Override
    public void unload() {
        try {
            /*if (hazelcastInstance != null) {
                // Forçar limpeza antes do shutdown
                forceCleanupResources();
                
                // Shutdown normal
                //hazelcastInstance.shutdown();
                FakeHazelcast.shutdown();

                // Esperar pelo shutdown
                long startTime = System.currentTimeMillis();
                while (hazelcastInstance.getLifecycleService().isRunning() && 
                       System.currentTimeMillis() - startTime < SHUTDOWN_TIMEOUT_SECONDS * 1000) {
                    Thread.sleep(100);
                }
                
                // Forçar shutdown se necessário
                if (hazelcastInstance.getLifecycleService().isRunning()) {
                    Trace.info("Forçando shutdown após timeout");
                    hazelcastInstance.getLifecycleService().terminate();
                }
                
                // Verificar recursos após shutdown
                monitorSystemResources();
            }*/
        } catch (Exception e) {
            Trace.error("Erro durante unload do HazelcastCacheManager", e);
        } finally {
            //hazelcastInstance = null;
        }
    }

    /*
    // Mantendo os métodos originais de configuração com as funcionalidades existentes
    private void configureDiagnostics(Config config, Properties props) {
        config.setProperty("hazelcast.diagnostics.enabled", 
            props.getProperty("env.HAZELCAST.diagnostics.enabled", "false"));
        config.setProperty("hazelcast.diagnostics.metric.level", 
            props.getProperty("env.HAZELCAST.diagnostics.metric.level", "info"));
        config.setProperty("hazelcast.diagnostics.invocation.sample.period.seconds", 
            props.getProperty("env.HAZELCAST.diagnostics.invocation.sample.period.seconds", "30"));
        config.setProperty("hazelcast.diagnostics.pending.invocations.period.seconds", 
            props.getProperty("env.HAZELCAST.diagnostics.pending.invocations.period.seconds", "30"));
        config.setProperty("hazelcast.diagnostics.slowoperations.period.seconds", 
            props.getProperty("env.HAZELCAST.diagnostics.slowoperations.period.seconds", "30"));
        config.setProperty("hazelcast.diagnostics.directory", 
            props.getProperty("env.HAZELCAST.diagnostics.directory", "logs"));
        config.setProperty("hazelcast.diagnostics.max.rolled.file.size.mb", 
            props.getProperty("env.HAZELCAST.diagnostics.max.rolled.file.size.mb", "50"));
        config.setProperty("hazelcast.diagnostics.max.rolled.file.count", 
            props.getProperty("env.HAZELCAST.diagnostics.max.rolled.file.count", "10"));
        config.setProperty("hazelcast.diagnostics.filename.prefix", 
            props.getProperty("env.HAZELCAST.diagnostics.filename.prefix", "diagnostics"));
    }
	*/
    /*private void configureLoggingAndMapSettings(Config config, Properties props) {
        // Configurações de logging
        config.setProperty("hazelcast.logging.level", 
            props.getProperty("env.HAZELCAST.logging.level", "INFO"));
    
        // Configurações específicas para rate limiting com foco em limpeza de memória
        MapConfig rateMapConfig = new MapConfig()
            .setName("rate-limit*")
            .setBackupCount(0)
            .setAsyncBackupCount(0)
            .setTimeToLiveSeconds(
                Integer.parseInt(props.getProperty("env.HAZELCAST.map.ttl.seconds", "3600")))
            .setMaxIdleSeconds(
                Integer.parseInt(props.getProperty("env.HAZELCAST.map.max.idle.seconds", "1800")));
    
        // Desabilitar persistência explicitamente
        rateMapConfig.setInMemoryFormat(InMemoryFormat.BINARY);
        rateMapConfig.getMapStoreConfig().setEnabled(false);  // Desabilita persistência
        rateMapConfig.setStatisticsEnabled(false);
        rateMapConfig.setPerEntryStatsEnabled(false);

        // Configuração de evicção mais agressiva
        EvictionConfig evictionConfig = new EvictionConfig()
            .setEvictionPolicy(EvictionPolicy.LRU)
            .setMaxSizePolicy(MaxSizePolicy.USED_HEAP_PERCENTAGE) // Mudança importante
            .setSize(70); // Limitar uso de heap a 70%
            
        rateMapConfig.setEvictionConfig(evictionConfig);
        
        config.addMapConfig(rateMapConfig);
        
        // Desabilitar hot restart
        config.getHotRestartPersistenceConfig().setEnabled(false);

        // Configurações adicionais para evitar persistência e backup
        config.setProperty("hazelcast.persistence.enabled", "false");
        config.setProperty("hazelcast.hot.restart.enabled", "false");
        config.setProperty("hazelcast.backup.redo.enabled", "false");
    
        // Configurações adicionais para limpeza de memória
        config.setProperty("hazelcast.map.eviction.batch.size", 
            props.getProperty("env.HAZELCAST.map.eviction.batch.size", "1000"));
        config.setProperty("hazelcast.map.eviction.cleanup.operation.count", 
            props.getProperty("env.HAZELCAST.map.eviction.cleanup.operation.count", "100"));
        config.setProperty("hazelcast.internal.map.expiration.cleanup.percentage", 
            props.getProperty("env.HAZELCAST.map.expiration.cleanup.percentage", "30"));
        config.setProperty("hazelcast.internal.map.expiration.task.period.seconds", 
            props.getProperty("env.HAZELCAST.map.expiration.task.period.seconds", "10"));
    }
*/

    /*private void logDetailedMemoryStatus() {
        try {
            List<MemoryPoolMXBean> memoryPoolMXBeans = ManagementFactory.getMemoryPoolMXBeans();
            for (MemoryPoolMXBean memoryPool : memoryPoolMXBeans) {
                if (memoryPool.getName().contains("Old Gen") || memoryPool.getName().contains("Tenured")) {
                    MemoryUsage usage = memoryPool.getUsage();
                    Trace.info(String.format("Old Gen Memory - Used: %dMB, Committed: %dMB, Max: %dMB", 
                        usage.getUsed() / (1024 * 1024),
                        usage.getCommitted() / (1024 * 1024),
                        usage.getMax() / (1024 * 1024)));
                }
            }
        } catch (Exception e) {
            Trace.error("Erro ao obter status detalhado da memória", e);
        }
    }*/

    /*private static void configureDiscovery(Config config, Properties props) {
        Trace.info("Configurando descoberta de membros...");
        NetworkConfig networkConfig = config.getNetworkConfig();
        JoinConfig joinConfig = networkConfig.getJoin();

        // Desabilitar multicast por padrão
        joinConfig.getMulticastConfig().setEnabled(false);

        String ipList = props.getProperty("env.HAZELCAST.member.ips");
        if (ipList != null && !ipList.isEmpty()) {
            configureTcpIpDiscovery(joinConfig, ipList);
        } else {
            configureKubernetesDiscovery(joinConfig, props);
        }
    }*/
    /*
    private void configureSystemResources(Config config, Properties props) {
        // Configurações anteriores
        config.setProperty("hazelcast.operation.thread.count", 
            props.getProperty("env.HAZELCAST.operation.thread.count", "2"));
        config.setProperty("hazelcast.operation.generic.thread.count", 
            props.getProperty("env.HAZELCAST.operation.generic.thread.count", "2"));
        config.setProperty("hazelcast.io.thread.count", 
            props.getProperty("env.HAZELCAST.io.thread.count", "2"));
        
        // Limitar conexões e sockets
        config.setProperty("hazelcast.socket.connect.timeout.seconds", "5");
        config.setProperty("hazelcast.socket.keep.alive", "true");
        config.setProperty("hazelcast.socket.no.delay", "true");
        config.setProperty("hazelcast.connection.monitor.interval", "10");
        config.setProperty("hazelcast.connection.monitor.max.faults", "2");
        
        // Forçar fechamento de conexões inativas
        config.setProperty("hazelcast.client.cleanup.timeout.millis", "30000");
        config.setProperty("hazelcast.socket.buffer.direct", "false");
    }
    */
    /*
    private void monitorSystemResources() {
        try {
            // Usar MXBean para obter informações do sistema operacional
            com.sun.management.UnixOperatingSystemMXBean osBean = getUnixOperatingSystemMXBean();
            
            if (osBean != null) {
                long openFiles = osBean.getOpenFileDescriptorCount();
                long maxFiles = osBean.getMaxFileDescriptorCount();
                
                Trace.info(String.format("File Descriptors - Abertos: %d, Máximo: %d, Uso: %.2f%%", 
                    openFiles, maxFiles, (openFiles * 100.0) / maxFiles));
                
                // Alertar se número de file descriptors estiver muito alto (acima de 85%)
                if (openFiles > (maxFiles * 0.85)) {
                    Trace.info("Número alto de file descriptors: " + openFiles + " de " + maxFiles);
                    forceCleanupResources();
                }
            }
            
        } catch (Exception e) {
            Trace.error("Erro ao monitorar recursos do sistema", e);
        }
    }*/
    
    /*private com.sun.management.UnixOperatingSystemMXBean getUnixOperatingSystemMXBean() {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        if (osBean instanceof com.sun.management.UnixOperatingSystemMXBean) {
            return (com.sun.management.UnixOperatingSystemMXBean) osBean;
        }
        return null;
    }*/
    /*
    private void forceCleanupResources() {
        if (hazelcastInstance != null) {
            try {
                
                // Forçar GC
                System.gc();
                
                Trace.info("Limpeza forçada de recursos concluída");
                
            } catch (Exception e) {
                Trace.error("Erro durante limpeza forçada de recursos", e);
            }
        }
    }*/

    /*private static void configureTcpIpDiscovery(JoinConfig joinConfig, String ipList) {
        Trace.info("Configurando descoberta de membros via TCP/IP...");
        TcpIpConfig tcpIpConfig = joinConfig.getTcpIpConfig();
        tcpIpConfig.setEnabled(true);
        
        for (String ip : ipList.split(",")) {
            tcpIpConfig.addMember(ip.trim());
        }
        
        // Configurações adicionais de TCP/IP via propriedades
        joinConfig.getTcpIpConfig().setEnabled(true);
    }

    private static void configureKubernetesDiscovery(JoinConfig joinConfig, Properties props) {
        Trace.info("Configurando descoberta de membros via Kubernetes...");
        KubernetesConfig kubernetesConfig = joinConfig.getKubernetesConfig();
        kubernetesConfig.setEnabled(true);

        // Configurações do Kubernetes com valores padrão otimizados
        String namespace = props.getProperty("env.HAZELCAST.namespace", "axway");
        String serviceName = props.getProperty("env.HAZELCAST.serviceName", "traffic");
        String servicePort = props.getProperty("env.HAZELCAST.servicePort", "5701");
        
        kubernetesConfig.setProperty("namespace", namespace);
        kubernetesConfig.setProperty("service-name", serviceName);
        kubernetesConfig.setProperty("service-port", servicePort);
        
        // Otimizações adicionais para Kubernetes
        kubernetesConfig.setProperty("resolve-not-ready-addresses", "false");
        kubernetesConfig.setProperty("kubernetes-master", 
            props.getProperty("env.HAZELCAST.kubernetes.master", "https://kubernetes.default.svc"));
    }

    // Método para verificar a saúde da instância
    public boolean isHealthy() {
        //return hazelcastInstance != null && 
        //       hazelcastInstance.getLifecycleService().isRunning() &&
        //       !hazelcastInstance.getCluster().getMembers().isEmpty();
        return FakeHazelcast.isRunning();

    }

    // Getter thread-safe para a instância
    public HazelcastInstance getHazelcastInstance() {
        return hazelcastInstance;
    }

    private void configureCPSubsystem(Config config, Properties props) {
        // Por padrão, define cp-member-count como 0 para desabilitar o CP Subsystem
        // O CP Subsystem será habilitado automaticamente quando necessário       
        config.getCPSubsystemConfig()
            .setCPMemberCount(Integer.parseInt(props.getProperty("env.HAZELCAST.cp.member.count", "0")))
            .setSessionTimeToLiveSeconds(Integer.parseInt(props.getProperty("env.HAZELCAST.cp.session.ttl.seconds", "300")))
            .setSessionHeartbeatIntervalSeconds(Integer.parseInt(props.getProperty("env.HAZELCAST.cp.session.heartbeat.interval.seconds", "5")));
    
        Trace.info("CP Subsystem configurado com configuração padrão");
    }
    
    private void configureBasicSettings(Config config, String port) {
        config.getNetworkConfig().setPort(Integer.parseInt(port));
        
        // Configurações básicas adicionais
        config.setProperty("hazelcast.partition.count", "271"); // Número primo para melhor distribuição
        config.setProperty("hazelcast.logging.type", "slf4j");
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.setProperty("hazelcast.jmx", "false");
    }
    
    private void configureNetworkSettings(Config config, Properties props) {
        if (config == null) {
            Trace.error("Config é null em configureNetworkSettings");
            throw new IllegalArgumentException("Config não pode ser null");
        }
    
        try {
            NetworkConfig networkConfig = config.getNetworkConfig();
            
            // Configuração de porta
            networkConfig.setPort(Integer.parseInt(props.getProperty("env.HAZELCAST.port", DEFAULT_PORT)));
            networkConfig.setPortAutoIncrement(true);
            networkConfig.setPortCount(100);
    
            // Habilitar logs do cluster
            config.setProperty("hazelcast.logging.level", "INFO");
            config.setProperty("hazelcast.logging.type", "slf4j");
            
            // Configurações de cluster
            config.setClusterName(props.getProperty("env.HAZELCAST.cluster.name", "axway-cluster"));
            
            // Configurações de interface
            networkConfig.getInterfaces().setEnabled(false);
            
            // Configurações de SSL
            SSLConfig sslConfig = new SSLConfig();
            sslConfig.setEnabled(false);
            networkConfig.setSSLConfig(sslConfig);
            
            // Configurações via propriedades
            config.setProperty("hazelcast.socket.receive.buffer.size", 
                props.getProperty("env.HAZELCAST.socket.receive.buffer.size", "256"));
            config.setProperty("hazelcast.socket.send.buffer.size", 
                props.getProperty("env.HAZELCAST.socket.send.buffer.size", "256"));
    
            // Configurações de join com mais logs
            JoinConfig joinConfig = networkConfig.getJoin();
            joinConfig.getMulticastConfig().setEnabled(false);
    
            String ipList = props.getProperty("env.HAZELCAST.member.ips");
            if (ipList != null && !ipList.isEmpty()) {
                Trace.info("Configurando membros TCP/IP: " + ipList);
                TcpIpConfig tcpIpConfig = joinConfig.getTcpIpConfig();
                tcpIpConfig.setEnabled(true);
                for (String ip : ipList.split(",")) {
                    tcpIpConfig.addMember(ip.trim());
                    Trace.info("Adicionado membro: " + ip.trim());
                }
            } else {
                Trace.info("Configurando descoberta via Kubernetes");
                KubernetesConfig kubernetesConfig = joinConfig.getKubernetesConfig();
                kubernetesConfig.setEnabled(true);
                
                String namespace = props.getProperty("env.HAZELCAST.namespace", "axway");
                String serviceName = props.getProperty("env.HAZELCAST.serviceName", "traffic");
                String servicePort = props.getProperty("env.HAZELCAST.servicePort", "5701");
                
                kubernetesConfig.setProperty("namespace", namespace);
                kubernetesConfig.setProperty("service-name", serviceName);
                kubernetesConfig.setProperty("service-port", servicePort);
                
                Trace.info("Kubernetes configurado - Namespace: " + namespace + 
                          ", Service: " + serviceName + 
                          ", Port: " + servicePort);
            }
    
            Trace.info("Configurações de rede aplicadas com sucesso");
            
        } catch (Exception e) {
            Trace.error("Erro ao configurar network settings", e);
            throw new RuntimeException("Falha ao configurar network settings", e);
        }
    }
    
    private void monitorClusterMembers(HazelcastInstance hazelcastInstance) {
        if (hazelcastInstance != null) {
            Set<Member> members = hazelcastInstance.getCluster().getMembers();
            Trace.info("Membros do cluster (" + members.size() + "):");
            for (Member member : members) {
                Trace.info("  - " + member.getAddress() + " (local: " + member.localMember() + ")");
            }
        }
        

    }
	
    private void configurePerformanceSettings(Config config, Properties props) {
        // Configurações de thread
        config.setProperty("hazelcast.operation.thread.count", 
            props.getProperty("env.HAZELCAST.operation.thread.count", 
                String.valueOf(Runtime.getRuntime().availableProcessors())));
        
        config.setProperty("hazelcast.io.thread.count", 
            props.getProperty("env.HAZELCAST.io.thread.count", "3"));
        
        // Configurações de performance
        config.setProperty("hazelcast.operation.generic.thread.count", 
            props.getProperty("env.HAZELCAST.operation.generic.thread.count", 
                String.valueOf(Runtime.getRuntime().availableProcessors())));
        
        config.setProperty("hazelcast.client.max.no.heartbeat.seconds", 
            props.getProperty("env.HAZELCAST.client.max.no.heartbeat.seconds", "300"));
        
        // Otimizações de execução
        config.setProperty("hazelcast.operation.execution.timeout.seconds", 
            props.getProperty("env.HAZELCAST.operation.execution.timeout.seconds", "60"));
        
        // Configurações de backup
        config.setProperty("hazelcast.backup.acks.enabled", 
            props.getProperty("env.HAZELCAST.backup.acks.enabled", "false"));
        
        // Configurações de partição
        config.setProperty("hazelcast.partition.migration.timeout", 
            props.getProperty("env.HAZELCAST.partition.migration.timeout", "300"));
        
        // Configurações de heartbeat
        config.setProperty("hazelcast.heartbeat.interval.seconds", 
            props.getProperty("env.HAZELCAST.heartbeat.interval.seconds", "5"));
    }
    
    private void configureTimeoutSettings(Config config, Properties props) {
        // Timeouts de operação
        config.setProperty("hazelcast.operation.call.timeout.millis", 
            props.getProperty("env.HAZELCAST.operation.call.timeout.millis", "120000"));
        
        config.setProperty("hazelcast.wan.replication.response.timeout.millis", 
            props.getProperty("env.HAZELCAST.wan.replication.response.timeout.millis", "60000"));
        
        // Timeouts de conexão
        config.setProperty("hazelcast.client.heartbeat.timeout", 
            props.getProperty("env.HAZELCAST.client.heartbeat.timeout", "60000"));
        
        config.setProperty("hazelcast.client.invocation.timeout.seconds", 
            props.getProperty("env.HAZELCAST.client.invocation.timeout.seconds", "120"));
        
        // Timeouts de operação em cluster
        config.setProperty("hazelcast.operation.backup.timeout.millis", 
            props.getProperty("env.HAZELCAST.operation.backup.timeout.millis", "5000"));
        
        config.setProperty("hazelcast.wait.seconds.before.join", 
            props.getProperty("env.HAZELCAST.wait.seconds.before.join", "5"));
        
        // Timeouts de migração
        config.setProperty("hazelcast.migration.min.delay.on.member.removed.seconds", 
            props.getProperty("env.HAZELCAST.migration.min.delay.seconds", "5"));
    }
    */
}