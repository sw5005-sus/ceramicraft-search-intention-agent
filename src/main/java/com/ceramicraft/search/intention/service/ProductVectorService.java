package com.ceramicraft.search.intention.service;

import com.ceramicraft.search.intention.model.ProductMessage;
import com.ceramicraft.search.intention.util.MarkdownUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 商品向量化服务 — 统一管理商品的 LLM 打标、向量化入库和删除操作。
 * <p>
 * <b>打标策略：</b> Redis 缓存 → LLM 打标
 * <ol>
 *   <li>优先从 Redis 缓存获取打标结果（命中则跳过 LLM 调用）</li>
 *   <li>未命中时调用 LLM 打标，并将结果写入缓存（TTL 可配，默认 7 天）</li>
 * </ol>
 * <p>
 * 同时维护 Redis 中的 {@code productName → documentId} 映射关系，
 * 以支持按商品名称高效删除向量库中的文档。
 * </p>
 */
@Service
public class ProductVectorService {

    private static final Logger log = LoggerFactory.getLogger(ProductVectorService.class);

    /** Redis key 前缀，用于存储 productName → documentId 的映射 */
    private static final String DOC_MAPPING_PREFIX = "ceramic:doc-mapping:";

    /** Redis key 前缀，用于缓存 LLM 打标结果（避免重复调用 LLM） */
    private static final String TAG_CACHE_PREFIX = "ceramic:tag-cache:";

    private final ProductTaggingService taggingService;
    private final VectorStore vectorStore;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 打标缓存 TTL（天），默认 7 天 */
    @Value("${ceramic.tagging.cache-ttl-days:7}")
    private int cacheTtlDays;

    public ProductVectorService(ProductTaggingService taggingService,
                                @Lazy VectorStore vectorStore,
                                ReactiveStringRedisTemplate redisTemplate,
                                ObjectMapper objectMapper) {
        this.taggingService = taggingService;
        this.vectorStore = vectorStore;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 对商品执行完整的打标 + 向量化 + 入库流程。
     * <p>
     * 优先从 Redis 缓存获取打标结果，命中则跳过 LLM 调用；
     * 未命中时调用 LLM 打标，并将结果写入缓存。
     * </p>
     */
    public Mono<Void> tagAndStore(ProductMessage product) {
        String cacheKey = TAG_CACHE_PREFIX + computeTagCacheKey(product);

        return redisTemplate.opsForValue().get(cacheKey)
                .doOnNext(cached -> log.info("⚡ 命中打标缓存 — ID: {}, 名称: {}", product.id(), product.name()))
                .switchIfEmpty(
                        taggingService.tagProduct(product.name(), product.category(), product.price(), product.desc())
                                .flatMap(result -> redisTemplate.opsForValue()
                                        .set(cacheKey, result, Duration.ofDays(cacheTtlDays))
                                        .thenReturn(result))
                )
                .flatMap(taggingResult -> {
                    String docId = "product-" + product.id();
                    Document document = buildDocument(docId, product, taggingResult);

                    return Mono.fromRunnable(() -> vectorStore.add(List.of(document)))
                            .subscribeOn(Schedulers.boundedElastic())
                            .then(redisTemplate.opsForValue()
                                    .set(DOC_MAPPING_PREFIX + product.name(), docId))
                            .then();
                })
                .doOnSuccess(v -> log.info("✅ 商品已打标并存入向量库 — ID: {}, 名称: {}",
                        product.id(), product.name()))
                .doOnError(ex -> log.error("❌ 商品打标入库失败 — ID: {}, 名称: {}, 错误: {}",
                        product.id(), product.name(), ex.getMessage()));
    }

    /**
     * 清除指定商品的打标缓存（用于强制重新打标）。
     */
    public Mono<Void> evictTagCache(ProductMessage product) {
        String cacheKey = TAG_CACHE_PREFIX + computeTagCacheKey(product);
        return redisTemplate.delete(cacheKey).then();
    }

    /**
     * 按商品名称从向量库中移除文档。
     */
    public Mono<Void> removeByName(String productName) {
        String mappingKey = DOC_MAPPING_PREFIX + productName;
        return redisTemplate.opsForValue().get(mappingKey)
                .flatMap(docId -> {
                    log.info("从向量库移除商品 — 名称: {}, docId: {}", productName, docId);
                    return Mono.fromRunnable(() -> vectorStore.delete(List.of(docId)))
                            .subscribeOn(Schedulers.boundedElastic())
                            .then(redisTemplate.delete(mappingKey))
                            .then();
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("未找到商品的向量库映射，跳过删除 — 名称: {}", productName);
                    return Mono.empty();
                }))
                .then();
    }

    // ==================== Document 构建 ====================

    private Document buildDocument(String docId, ProductMessage product, String taggingResult) {
        Map<String, Object> metadata = new HashMap<>();
        // 所有值转 String 存储，避免 Redis 反序列化后类型丢失
        metadata.put("productId", String.valueOf(product.id()));
        metadata.put("name", product.name());
        metadata.put("category", product.category());
        metadata.put("price", String.valueOf(product.price()));
        metadata.put("desc", product.desc());
        metadata.put("stock", String.valueOf(product.stock()));
        metadata.put("picInfo", product.picInfo());
        metadata.put("status", String.valueOf(product.status()));

        String text = product.name() + " — " + product.desc();

        try {
            String cleanJson = MarkdownUtils.cleanMarkdown(taggingResult);
            JsonNode node = objectMapper.readTree(cleanJson);

            putIfPresent(metadata, node, "material");
            putIfPresent(metadata, node, "style");
            putIfPresent(metadata, node, "origin");
            putIfPresent(metadata, node, "occasion");
            putIfPresent(metadata, node, "tags");

            if (node.has("enrichedDescription") && !node.get("enrichedDescription").isNull()) {
                String enriched = node.get("enrichedDescription").asText();
                if (!enriched.isBlank()) {
                    text = enriched;
                }
            }
        } catch (Exception ex) {
            log.warn("标签 JSON 解析失败，使用原始描述入库: {}", ex.getMessage());
        }

        return new Document(docId, text, metadata);
    }

    private void putIfPresent(Map<String, Object> metadata, JsonNode node, String field) {
        if (node.has(field) && !node.get(field).isNull()) {
            metadata.put(field, node.get(field).asText());
        }
    }


    /**
     * 根据商品的打标输入字段计算 SHA-256 哈希值，作为缓存 Key。
     * 只要 name + category + price + desc 不变，LLM 打标结果就可复用。
     */
    private String computeTagCacheKey(ProductMessage product) {
        try {
            String raw = product.name() + "|" + product.category() + "|" + product.price() + "|" + product.desc();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            // 降级：使用 productId
            return "pid-" + product.id();
        }
    }
}
