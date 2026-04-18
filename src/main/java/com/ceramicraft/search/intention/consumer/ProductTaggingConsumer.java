package com.ceramicraft.search.intention.consumer;

import com.ceramicraft.search.intention.model.ProductUploadEvent;
import com.ceramicraft.search.intention.service.ProductApiClient;
import com.ceramicraft.search.intention.service.ProductVectorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 商品变更 Kafka 消费者。
 * <p>
 * 监听 {@code product_changed} Topic，根据操作类型执行相应处理：
 * <ul>
 *   <li>{@code upload} — 新商品上架：从商品 API 拉取完整数据 → LLM 打标 → 写入向量库</li>
 *   <li>{@code update} — 商品更新：重新拉取 → 打标 → 覆盖写入向量库</li>
 *   <li>{@code delete} — 商品下架/删除：从向量库中移除对应文档</li>
 * </ul>
 * </p>
 */
@Component
public class ProductTaggingConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProductTaggingConsumer.class);

    private final ProductApiClient apiClient;
    private final ProductVectorService vectorService;
    private final ObjectMapper objectMapper;

    public ProductTaggingConsumer(ProductApiClient apiClient,
                                  ProductVectorService vectorService,
                                  ObjectMapper objectMapper) {
        this.apiClient = apiClient;
        this.vectorService = vectorService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        log.info("✅ [Kafka Consumer] ProductTaggingConsumer 已创建，监听 Topic: product_changed");
    }

    /**
     * Kafka 消息监听入口。
     * <p>
     * 消息格式：{@code {"productName": "xxx", "operation": "upload|update|delete"}}
     * </p>
     */
    @KafkaListener(
            id = "product-search-listener",
            topics = "${ceramic.kafka.topic.product-search:product_changed}",
            groupId = "ceramic-product-search-group",
            concurrency = "1"
    )
    public void onProductSearchEvent(String message) {
        log.info("========== [Kafka] 收到商品变更事件 ==========");
        log.debug("[Kafka] 原始消息: {}", message);

        try {
            ProductUploadEvent event = objectMapper.readValue(message, ProductUploadEvent.class);
            log.info("[Kafka] 商品: {}, 操作: {}", event.productName(), event.operation());

            switch (event.operation().toLowerCase()) {
                case "upload", "update" -> handleUploadOrUpdate(event.productName());
                case "delete" -> handleDelete(event.productName());
                default -> log.warn("[Kafka] 未知操作类型: {}", event.operation());
            }
        } catch (Exception ex) {
            log.error("❌ [Kafka] 处理商品变更事件失败: {}", ex.getMessage(), ex);
        }
    }

    /**
     * 处理商品上传/更新：从 API 拉取完整数据 → LLM 打标 → 写入/覆盖向量库。
     */
    private void handleUploadOrUpdate(String productName) {
        log.info("[Kafka] 处理 upload/update — 从商品 API 拉取数据: {}", productName);

        var product = apiClient.fetchProductByName(productName).block();
        if (product == null) {
            log.warn("[Kafka] 商品 API 中未找到 '{}'，跳过", productName);
            return;
        }

        log.info("[Kafka] 获取到商品 — ID: {}, 名称: {}, 开始打标入库...", product.id(), product.name());
        vectorService.tagAndStore(product).block();
        log.info("[Kafka] ✅ 商品 '{}' 打标入库完成", productName);
    }

    /**
     * 处理商品删除/下架：从向量库和 Redis 映射中删除对应文档。
     */
    private void handleDelete(String productName) {
        log.info("[Kafka] 处理 delete — 从向量库移除: {}", productName);
        vectorService.removeByName(productName).block();
        log.info("[Kafka] ✅ 商品 '{}' 已从向量库移除", productName);
    }
}
