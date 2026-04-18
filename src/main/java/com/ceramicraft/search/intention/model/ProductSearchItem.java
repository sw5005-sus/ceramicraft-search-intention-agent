package com.ceramicraft.search.intention.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * 语义搜索结果中的单条商品项。
 */
@Schema(description = "Product item in semantic search results")
public record ProductSearchItem(
        @Schema(description = "Product ID", example = "42")
        int id,
        @Schema(description = "Product name", example = "Handmade celadon teacup")
        String name,
        @Schema(description = "Product category", example = "teaware")
        String category,
        @Schema(description = "Product price", example = "128")
        int price,
        @Schema(description = "Product description")
        String desc,
        @Schema(description = "Stock quantity", example = "50")
        int stock,
        @Schema(description = "Picture info (JSON array string)")
        @JsonProperty("pic_info") String picInfo,
        @Schema(description = "Product status (1=active, 0=inactive)", example = "1")
        int status,
        @Schema(description = "Material tag (AI-generated)", example = "celadon")
        String material,
        @Schema(description = "Style tag (AI-generated)", example = "Chinese classical")
        String style,
        @Schema(description = "Origin tag (AI-generated)", example = "Jingdezhen")
        String origin,
        @Schema(description = "Occasion tag (AI-generated)", example = "gift")
        String occasion,
        @Schema(description = "Search tags (AI-generated, comma-separated)", example = "teacup,celadon,handmade,tea ceremony")
        String tags,
        @Schema(description = "Semantic relevance score (0.0~1.0)", example = "0.8723")
        double score
) {

    /**
     * 从 Spring AI Document 的 metadata 构建搜索结果项。
     */
    public static ProductSearchItem fromDocument(Map<String, Object> metadata, String text, double score) {
        return new ProductSearchItem(
                toInt(metadata.get("productId")),
                toStr(metadata.get("name")),
                toStr(metadata.get("category")),
                toInt(metadata.get("price")),
                toStr(metadata.get("desc")),
                toInt(metadata.get("stock")),
                toStr(metadata.get("picInfo")),
                toInt(metadata.get("status")),
                toStr(metadata.get("material")),
                toStr(metadata.get("style")),
                toStr(metadata.get("origin")),
                toStr(metadata.get("occasion")),
                toStr(metadata.get("tags")),
                Math.round(score * 10000.0) / 10000.0
        );
    }

    private static String toStr(Object value) {
        return value == null ? null : value.toString();
    }

    private static int toInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(value.toString()); }
        catch (NumberFormatException e) { return 0; }
    }
}
