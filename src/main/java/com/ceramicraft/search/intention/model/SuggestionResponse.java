package com.ceramicraft.search.intention.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Search suggestion response")
public record SuggestionResponse(
        @Schema(description = "Status code (200=success)", example = "200")
        int code,
        @Schema(description = "User ID (null for anonymous)", example = "user-001")
        String userId,
        @Schema(description = "List of search suggestions")
        List<SuggestionItem> suggestions,
        @Schema(description = "Recommendation source description", example = "Based on your recent search preferences + AI reasoning")
        String source
) {

    @Schema(description = "Single search suggestion item")
    public record SuggestionItem(
            @Schema(description = "Recommended search keyword", example = "handmade celadon tea set")
            String keyword,
            @Schema(description = "Recommendation reason (user-facing)", example = "You might also be interested in...")
            String reason,
            @Schema(description = "Suggestion type: HISTORY_BASED / TRENDING / DISCOVERY / DEFAULT", example = "HISTORY_BASED")
            String type
    ) {}

    /** 成功响应 */
    public static SuggestionResponse ok(String userId, List<SuggestionItem> suggestions, String source) {
        return new SuggestionResponse(200, userId, suggestions, source);
    }

    /** 错误降级响应 */
    public static SuggestionResponse error(String userId, String errorMsg) {
        return new SuggestionResponse(500, userId, List.of(),
                "Recommendation service temporarily unavailable: " + errorMsg);
    }
}

