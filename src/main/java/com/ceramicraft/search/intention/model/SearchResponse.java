package com.ceramicraft.search.intention.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 语义搜索接口的统一响应体。
 *
 * @param code     状态码（200 成功）
 * @param query    用户原始查询
 * @param total    匹配商品数量
 * @param products 推荐商品列表（按语义相关度降序）
 * @param hint     前端提示信息
 */
@Schema(description = "Semantic search response")
public record SearchResponse(
        @Schema(description = "Status code (200=success)", example = "200")
        int code,
        @Schema(description = "Original search query", example = "celadon teacup")
        String query,
        @Schema(description = "Number of matching products", example = "5")
        int total,
        @Schema(description = "Matching products sorted by semantic relevance")
        List<ProductSearchItem> products,
        @Schema(description = "AI recommendation summary (RAG mode only, null for basic search)")
        String recommendation,
        @Schema(description = "Hint message for the client")
        String hint
) {

    /** 成功响应（纯向量检索，无 AI 推荐摘要） */
    public static SearchResponse ok(String query, List<ProductSearchItem> products) {
        return new SearchResponse(
                200, query, products.size(), products, null,
                "Results from semantic search index. For real-time price/stock, query the product service by ID."
        );
    }

    /** 成功响应（RAG 模式，包含 AI 推荐摘要） */
    public static SearchResponse okWithRecommendation(String query, List<ProductSearchItem> products,
                                                       String recommendation) {
        return new SearchResponse(
                200, query, products.size(), products, recommendation,
                "Results from AI RAG recommendation, including semantic retrieval + LLM analysis."
        );
    }

    /** 错误响应 */
    public static SearchResponse error(String query, String errorMsg) {
        return new SearchResponse(500, query, 0, List.of(), null, errorMsg);
    }
}
