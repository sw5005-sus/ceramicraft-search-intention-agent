package com.ceramicraft.search.intention.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 原始商品数据 DTO。
 * <p>
 * 对应商品微服务返回的 {@code data.list[]} 中的单个商品对象。
 * </p>
 * <p>API 响应格式：</p>
 * <pre>
 * { "code": 200, "data": { "list": [ { "id":100, "name":"...", ... } ], "total":100 }, "err_msg":"ok" }
 * </pre>
 *
 * @param id       商品 ID
 * @param name     商品名称
 * @param category 商品类目（如 pottery, ceramics, vases, decorations）
 * @param price    商品价格（新加坡元分）
 * @param desc     商品描述
 * @param stock    库存数量
 * @param picInfo  图片信息（JSON 数组字符串）
 * @param status   商品状态（1=上架, 0=下架）
 */
public record ProductMessage(
        int id,
        String name,
        String category,
        int price,
        String desc,
        int stock,
        @JsonProperty("pic_info") String picInfo,
        int status
) {}
