package com.ceramicraft.search.intention.model;

/**
 * Kafka 商品变更事件 DTO。
 * <p>
 * 由上游商品微服务在商品上传/更新/删除时发送到 {@code product_changed} Topic。
 * 消费者根据 {@code operation} 字段执行不同操作：
 * <ul>
 *   <li>{@code upload} — 新商品上架，从 API 拉取完整数据后打标入库</li>
 *   <li>{@code update} — 商品信息更新，重新拉取 → 打标 → 覆盖写入向量库</li>
 *   <li>{@code delete} — 商品下架/删除，从向量库中移除</li>
 * </ul>
 * </p>
 *
 * @param productName 商品名称
 * @param operation   操作类型：upload / update / delete
 */
public record ProductUploadEvent(
        String productName,
        String operation
) {}
