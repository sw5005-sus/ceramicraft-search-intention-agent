package com.ceramicraft.search.intention.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore.MetadataField;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPooled;

import java.net.URI;
import java.util.List;

/**
 * 陶瓷商品向量数据库配置。
 * <p>
 * 基于 Redis Search 模块构建向量索引，用于 RAG 检索。
 * 自定义 Metadata 字段映射，覆盖陶瓷电商场景下的核心商品属性，
 * 以便在相似度搜索时支持混合过滤（向量 + 属性）。
 * </p>
 */
@Configuration
@EnableConfigurationProperties(PromptConfig.class)
public class CeramicVectorStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(CeramicVectorStoreConfig.class);

    @Value("${spring.ai.vectorstore.redis.index:ceramic-product-index}")
    private String indexName;

    @Value("${spring.ai.vectorstore.redis.prefix:ceramic:}")
    private String prefix;

    /**
     * 手动创建 Jedis 连接池。
     * <p>
     * 由于本配置类自定义了 {@link VectorStore} Bean，Spring AI 的自动配置
     * （包括其内部的 JedisPooled Bean）会被整体跳过，因此需要在这里显式创建。
     * </p>
     *
     * @param redisUri Redis 连接 URI，读取自 spring.ai.vectorstore.redis.uri
     * @return JedisPooled 连接池实例
     */
    @Bean
    public JedisPooled jedisPooled(
            @Value("${spring.ai.vectorstore.redis.uri:redis://localhost:6379}") String redisUri) {
        log.info("初始化 JedisPooled 连接 — URI: {}", redisUri);
        return new JedisPooled(URI.create(redisUri));
    }

    /**
     * 创建自定义的 Redis 向量存储实例。
     * <p>
     * 覆盖 Spring AI 自动配置的默认 {@link VectorStore} Bean，
     * 以注册陶瓷电商领域特有的 Metadata 字段。
     * 这些字段将在 Redis 中建立二级索引，支持属性级别的混合检索。
     * </p>
     *
     * <h3>注册的 Metadata 字段：</h3>
     * <ul>
     *   <li><b>price</b>    — NUMERIC：商品价格（新加坡元分），支持范围过滤</li>
     *   <li><b>material</b> — TEXT：材质（如青瓷、紫砂、骨瓷），支持全文匹配</li>
     *   <li><b>style</b>    — TEXT：风格（如中式古典、日式简约），支持全文匹配</li>
     *   <li><b>category</b> — TEXT：商品类目（如茶杯、花瓶），支持全文匹配</li>
     *   <li><b>origin</b>   — TEXT：产地（如景德镇、德化、宜兴），支持全文匹配</li>
     *   <li><b>tags</b>     — TAG：商品标签（多值，逗号分隔），支持精确标签过滤</li>
     * </ul>
     *
     * @param embeddingModel Spring AI 嵌入模型（由 OpenAI Starter 自动注入）
     * @param jedisPooled    Jedis 连接池（由 Redis Starter 自动注入）
     * @return 配置完毕的 {@link RedisVectorStore} 实例
     */
    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel, JedisPooled jedisPooled) {

        // 定义陶瓷电商领域的 Metadata 字段映射
        // ⚠️ 必须注册所有需要在搜索结果中返回的字段，否则 Redis FT.SEARCH 不会返回未索引的字段
        // ⚠️ 所有字段统一使用 TEXT 类型（因 metadata 中数值也以 String 存储）
        // Redis FT 的 NUMERIC 字段要求 JSON 值为数字类型，但 Spring AI metadata 全部序列化为 String，
        // 若类型不匹配会导致 "Invalid JSON type: String type can represent only TEXT..." 索引失败
        var ceramicMetadataFields = List.of(
                MetadataField.text("productId"),       // 商品 ID：用于回查商品后端
                MetadataField.text("name"),            // 商品名称：搜索结果展示
                MetadataField.text("price"),           // 价格：以文本形式存储（不做范围查询）
                MetadataField.text("desc"),            // 商品描述：原始描述文本
                MetadataField.text("stock"),           // 库存数量
                MetadataField.text("picInfo"),         // 图片信息：JSON 数组字符串
                MetadataField.text("status"),          // 商品状态：1=上架 0=下架
                MetadataField.text("material"),        // 材质：青瓷、白瓷、紫砂、骨瓷、粗陶 …
                MetadataField.text("style"),           // 风格：中式古典、日式简约、北欧现代 …
                MetadataField.text("category"),        // 类目：茶杯、花瓶、餐盘、茶壶、摆件 …
                MetadataField.text("origin"),          // 产地：景德镇、德化、宜兴、龙泉 …
                MetadataField.text("occasion"),        // 场景：送礼、日常家用、收藏、婚庆 …
                MetadataField.tag("tags")              // 标签：手工,限量,大师作品 …
        );

        log.info("初始化 Redis 向量存储 — 索引名: {}, 前缀: {}, 元数据字段数: {}",
                indexName, prefix, ceramicMetadataFields.size());

        return RedisVectorStore.builder(jedisPooled, embeddingModel)
                .indexName(indexName)
                .prefix(prefix)
                .metadataFields(ceramicMetadataFields)
                .initializeSchema(true)   // 首次使用时自动创建索引 Schema
                .build();
    }
}

