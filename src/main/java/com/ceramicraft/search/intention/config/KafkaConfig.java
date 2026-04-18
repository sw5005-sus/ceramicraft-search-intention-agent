package com.ceramicraft.search.intention.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka 配置类。
 * <p>
 * ConsumerFactory / ContainerFactory 由 Spring Boot 根据 application.yml 自动配置。
 * 本类只负责：(1) Topic 创建  (2) 启动诊断。
 * </p>
 */
@EnableKafka
@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    @Value("${ceramic.kafka.topic.product-search:product_changed}")
    private String productSearchTopic;

    @Bean
    public NewTopic productSearchTopic() {
        log.info("✅ [Kafka] 创建 Topic Bean — name: {}", productSearchTopic);
        return TopicBuilder.name(productSearchTopic)
                .partitions(1)
                .replicas(1)
                .build();
    }

    /**
     * 应用启动完成后，诊断并手动启动 Kafka Listener 容器。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        log.info("========== [Kafka 诊断] ApplicationReady 事件触发 ==========");

        KafkaListenerEndpointRegistry registry =
                event.getApplicationContext().getBean(KafkaListenerEndpointRegistry.class);

        var ids = registry.getListenerContainerIds();
        log.info("[Kafka 诊断] 已注册的 Listener 数量: {}, IDs: {}", ids.size(), ids);

        if (ids.isEmpty()) {
            log.warn("[Kafka 诊断] ⚠️ 没有注册任何 @KafkaListener！请检查 ProductTaggingConsumer 是否被 Spring 扫描到。");
        }

        ids.forEach(id -> {
            var container = registry.getListenerContainer(id);
            if (container != null) {
                boolean running = container.isRunning();
                log.info("[Kafka 诊断] Container '{}' — running: {}, autoStartup: {}",
                        id, running, container.isAutoStartup());
                if (!running) {
                    log.info("[Kafka 诊断] 手动启动 Container '{}'...", id);
                    container.start();
                    log.info("[Kafka 诊断] ✅ Container '{}' 已手动启动, running={}", id, container.isRunning());
                }
            }
        });
    }
}
