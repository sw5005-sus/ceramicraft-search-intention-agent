package com.ceramicraft.search.intention.controller;

import com.ceramicraft.search.intention.model.SearchResponse;
import com.ceramicraft.search.intention.model.SuggestionResponse;
import com.ceramicraft.search.intention.model.SuggestionResponse.SuggestionItem;
import com.ceramicraft.search.intention.service.ProductSearchService;
import com.ceramicraft.search.intention.service.SearchHistoryService;
import com.ceramicraft.search.intention.service.SearchSuggestionService;
import com.ceramicraft.search.intention.util.PromptGuardUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/v1/customer/search")
@Tag(name = "Product Search", description = "Semantic search, history, hot searches, and smart suggestions")
public class ProductSearchController {

    private static final Logger log = LoggerFactory.getLogger(ProductSearchController.class);

    private final ProductSearchService searchService;
    private final SearchHistoryService historyService;
    private final SearchSuggestionService suggestionService;

    public ProductSearchController(ProductSearchService searchService,
                                   SearchHistoryService historyService,
                                   SearchSuggestionService suggestionService) {
        this.searchService = searchService;
        this.historyService = historyService;
        this.suggestionService = suggestionService;
    }

    // ==================== 语义搜索 ====================

    @Operation(summary = "Semantic product search",
            description = "Natural language query -> vector semantic matching. Pass X-Original-User-ID header to record search history.")
    @GetMapping
    public Mono<SearchResponse> search(
            @Parameter(description = "Natural language search query", example = "handmade celadon teacup")
            @RequestParam("query") String query,
            @Parameter(description = "Max results (1~50)", example = "10")
            @RequestParam(value = "limit", defaultValue = "10") int limit,
            @Parameter(description = "User ID for search history (optional)")
            @RequestHeader(value = "X-Original-User-ID", required = false) String userId) {

        // ===== Prompt Injection 防护 =====
        String safeQuery = PromptGuardUtils.sanitizeQuery(query);
        if (!PromptGuardUtils.isValidQuery(safeQuery)) {
            return Mono.just(SearchResponse.error(query, "请输入有效的搜索内容"));
        }
        PromptGuardUtils.RiskLevel risk = PromptGuardUtils.detectRisk(safeQuery);
        if (risk == PromptGuardUtils.RiskLevel.BLOCKED) {
            log.warn("🛡️ 搜索请求被拦截（Prompt Injection） — query: '{}', userId: {}", query, userId);
            return Mono.just(SearchResponse.error(query, "搜索内容包含不允许的指令，请输入正常的商品搜索词"));
        }

        log.info("语义搜索请求 — query: '{}', limit: {}, userId: {}", safeQuery, limit, userId);
        int safeLimit = Math.min(Math.max(limit, 1), 50);

        // 搜索 + 异步记录历史（不阻塞搜索结果返回）
        Mono<Void> recordMono = historyService.record(userId, safeQuery)
                .onErrorResume(ex -> {
                    log.warn("搜索历史记录失败（不影响搜索结果）: {}", ex.getMessage());
                    return Mono.empty();
                });

        return searchService.search(safeQuery, safeLimit)
                .map(products -> SearchResponse.ok(safeQuery, products))
                .delayUntil(resp -> recordMono)
                .onErrorResume(ex -> {
                    log.error("语义搜索失败 — query: '{}'", query, ex);
                    return Mono.just(SearchResponse.error(query, ex.getMessage()));
                });
    }

    @Operation(summary = "Find similar products",
            description = "Given a product name, find semantically similar products from the vector store.")
    @GetMapping("/similar")
    public Mono<SearchResponse> findSimilar(
            @Parameter(description = "Base product name", example = "Blue-and-white porcelain vase")
            @RequestParam("productName") String productName,
            @Parameter(description = "Max results (1~20)", example = "5")
            @RequestParam(value = "limit", defaultValue = "5") int limit) {

        // ===== Prompt Injection 防护 =====
        String safeName = PromptGuardUtils.sanitizeQuery(productName);
        if (!PromptGuardUtils.isValidQuery(safeName)) {
            return Mono.just(SearchResponse.error(productName, "请输入有效的商品名称"));
        }
        if (PromptGuardUtils.detectRisk(safeName) == PromptGuardUtils.RiskLevel.BLOCKED) {
            log.warn("🛡️ 相似推荐请求被拦截 — productName: '{}'", productName);
            return Mono.just(SearchResponse.error(productName, "输入内容包含不允许的指令"));
        }

        log.info("相似商品推荐 — productName: '{}', limit: {}", safeName, limit);

        return searchService.findSimilar(safeName, Math.min(limit, 20))
                .map(products -> SearchResponse.ok(productName, products))
                .onErrorResume(ex -> {
                    log.error("相似推荐失败 — productName: '{}'", productName, ex);
                    return Mono.just(SearchResponse.error(productName, ex.getMessage()));
                });
    }

    // ==================== RAG 智能搜索（向量检索 + LLM 推荐） ====================

    @Operation(summary = "RAG-powered product search",
            description = "Semantic vector search + LLM recommendation summary. "
                    + "Returns product list AND AI-generated recommendation text. Slower than basic search (~3-8s).")
    @GetMapping("/rag")
    public Mono<SearchResponse> ragSearch(
            @Parameter(description = "Natural language search query", example = "premium celadon teacup as gift for elders")
            @RequestParam("query") String query,
            @Parameter(description = "Max results (1~20)", example = "5")
            @RequestParam(value = "limit", defaultValue = "5") int limit,
            @Parameter(description = "User ID for search history (optional)")
            @RequestHeader(value = "X-Original-User-ID", required = false) String userId) {

        // ===== Prompt Injection 防护 =====
        String safeQuery = PromptGuardUtils.sanitizeQuery(query);
        if (!PromptGuardUtils.isValidQuery(safeQuery)) {
            return Mono.just(SearchResponse.error(query, "请输入有效的搜索内容"));
        }
        if (PromptGuardUtils.detectRisk(safeQuery) == PromptGuardUtils.RiskLevel.BLOCKED) {
            log.warn("🛡️ RAG 搜索请求被拦截（Prompt Injection） — query: '{}', userId: {}", query, userId);
            return Mono.just(SearchResponse.error(query, "搜索内容包含不允许的指令，请输入正常的商品搜索词"));
        }

        log.info("RAG 智能搜索请求 — query: '{}', limit: {}, userId: {}", safeQuery, limit, userId);
        int safeLimit = Math.min(Math.max(limit, 1), 20);

        // 异步记录搜索历史
        Mono<Void> recordMono = historyService.record(userId, safeQuery)
                .onErrorResume(ex -> {
                    log.warn("搜索历史记录失败（不影响搜索结果）: {}", ex.getMessage());
                    return Mono.empty();
                });

        return searchService.ragSearch(safeQuery, safeLimit)
                .map(result -> SearchResponse.okWithRecommendation(
                        safeQuery, result.products(), result.recommendation()))
                .delayUntil(resp -> recordMono)
                .onErrorResume(ex -> {
                    log.error("RAG 智能搜索失败 — query: '{}'", query, ex);
                    return Mono.just(SearchResponse.error(query, ex.getMessage()));
                });
    }

    @Operation(summary = "RAG-powered search recommendation (SSE streaming)",
            description = "Streams AI recommendation text via SSE based on vector-retrieved products. "
                    + "Use alongside GET /v1/customer/search for two-phase rendering:"
                    + "fast product list + async AI recommendation. Cannot be tested in Swagger UI.")
    @GetMapping(value = "/rag/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> ragSearchStream(
            @Parameter(description = "Natural language search query", example = "premium celadon teacup as gift for elders")
            @RequestParam("query") String query,
            @Parameter(description = "Max products for context (1~20)", example = "5")
            @RequestParam(value = "limit", defaultValue = "5") int limit) {

        // ===== Prompt Injection 防护 =====
        String safeQuery = PromptGuardUtils.sanitizeQuery(query);
        if (!PromptGuardUtils.isValidQuery(safeQuery)) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error").data("请输入有效的搜索内容").build());
        }
        if (PromptGuardUtils.detectRisk(safeQuery) == PromptGuardUtils.RiskLevel.BLOCKED) {
            log.warn("🛡️ RAG 流式搜索被拦截（Prompt Injection） — query: '{}'", query);
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error").data("搜索内容包含不允许的指令，请输入正常的商品搜索词").build());
        }

        log.info("RAG 流式搜索请求 — query: '{}', limit: {}", safeQuery, limit);

        return searchService.ragSearchStream(safeQuery, Math.min(Math.max(limit, 1), 20))
                .map(token -> ServerSentEvent.<String>builder()
                        .data(token)
                        .build())
                .concatWith(Flux.just(
                        ServerSentEvent.<String>builder()
                                .event("complete")
                                .data("[DONE]")
                                .build()
                ))
                .onErrorResume(ex -> {
                    log.error("RAG 流式搜索异常 — query: {}", query, ex);
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event("error")
                            .data("AI 推荐服务暂时不可用，请稍后重试。")
                            .build());
                })
                .timeout(Duration.ofSeconds(30))
                .onErrorResume(java.util.concurrent.TimeoutException.class, ex ->
                        Flux.just(ServerSentEvent.<String>builder()
                                .event("error")
                                .data("AI 推荐生成超时，请稍后重试。")
                                .build()));
    }

    // ==================== 搜索历史（需登录） ====================

    @Operation(summary = "Get user search history",
            description = "Returns the user's recent search history. Requires X-Original-User-ID header.")
    @GetMapping("/history")
    public Mono<Map<String, Object>> getHistory(
            @Parameter(description = "User ID", required = true, example = "user-001")
            @RequestHeader("X-Original-User-ID") String userId,
            @Parameter(description = "Max history items (1~50)", example = "10")
            @RequestParam(value = "limit", defaultValue = "10") int limit) {

        return historyService.getHistory(userId, Math.min(limit, 50))
                .map(list -> {
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("code", 200);
                    resp.put("userId", userId);
                    resp.put("total", list.size());
                    resp.put("history", list);
                    return resp;
                });
    }

    @Operation(summary = "Clear user search history",
            description = "Deletes all search history for the specified user. Requires X-Original-User-ID header.")
    @DeleteMapping("/history")
    public Mono<Map<String, Object>> clearHistory(
            @Parameter(description = "User ID", required = true, example = "user-001")
            @RequestHeader("X-Original-User-ID") String userId) {

        return historyService.clearHistory(userId)
                .then(Mono.fromSupplier(() -> {
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("code", 200);
                    resp.put("message", "搜索历史已清空");
                    resp.put("userId", userId);
                    return resp;
                }));
    }

    // ==================== 热搜排行 ====================

    @Operation(summary = "Get trending searches",
            description = "Returns site-wide trending search keywords ranked by search count.")
    @GetMapping("/hot")
    public Mono<Map<String, Object>> getHotSearches(
            @Parameter(description = "Max results (1~30)", example = "10")
            @RequestParam(value = "limit", defaultValue = "10") int limit) {

        return historyService.getHotSearches(Math.min(limit, 30))
                .map(list -> {
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("code", 200);
                    resp.put("total", list.size());
                    resp.put("hotSearches", list);
                    return resp;
                });
    }

    // ==================== 猜你想搜 ====================

    @Operation(summary = "Smart search suggestions",
            description = "Anonymous: returns random product names. Logged-in: LLM-powered personalized recommendations.")
    @GetMapping("/suggestions")
    public Mono<SuggestionResponse> getSuggestions(
            @Parameter(description = "User ID (optional)")
            @RequestHeader(value = "X-Original-User-ID", required = false) String userId,
            @Parameter(description = "Number of suggestions (1~15)", example = "8")
            @RequestParam(value = "limit", defaultValue = "8") int limit) {

        int safeLimit = Math.min(Math.max(limit, 1), 15);
        boolean hasUser = userId != null && !userId.isBlank();
        log.info("猜你想搜请求 — userId: {}, limit: {}, 模式: {}",
                userId, safeLimit, hasUser ? "LLM 智能推荐" : "随机商品推荐");

        if (!hasUser) {
            // 匿名用户 → 从 Redis 随机取商品名称
            return historyService.getRandomProductNames(safeLimit)
                    .map(names -> {
                        var items = names.stream()
                                .map(name -> new SuggestionItem(name, "热门推荐", "DEFAULT"))
                                .toList();
                        return SuggestionResponse.ok(null, items, "基于平台热门商品");
                    });
        }

        // 登录用户 → LLM 智能推荐
        return suggestionService.suggest(userId, safeLimit);
    }

    @Operation(summary = "Smart suggestions (SSE streaming)",
            description = "LLM-powered suggestions via SSE. Cannot be tested in Swagger UI - use curl or EventSource.")
    @GetMapping(value = "/suggestions/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamSuggestions(
            @Parameter(description = "User ID (optional)")
            @RequestHeader(value = "X-Original-User-ID", required = false) String userId,
            @Parameter(description = "Number of suggestions (1~15)", example = "6")
            @RequestParam(value = "limit", defaultValue = "6") int limit) {

        log.info("猜你想搜(流式)请求 — userId: {}, limit: {}", userId, limit);

        return suggestionService.suggestStream(userId, Math.min(Math.max(limit, 1), 15))
                .map(token -> ServerSentEvent.<String>builder()
                        .data(token)
                        .build())
                .concatWith(Flux.just(
                        ServerSentEvent.<String>builder()
                                .event("complete")
                                .data("[DONE]")
                                .build()
                ))
                .onErrorResume(ex -> {
                    log.error("智能推荐流式异常 — userId: {}", userId, ex);
                    return Flux.just(
                            ServerSentEvent.<String>builder()
                                    .event("error")
                                    .data("推荐服务暂时不可用，请稍后重试。")
                                    .build()
                    );
                })
                .timeout(Duration.ofSeconds(30))
                .onErrorResume(java.util.concurrent.TimeoutException.class, ex ->
                        Flux.just(ServerSentEvent.<String>builder()
                                .event("error")
                                .data("推荐生成超时，请稍后重试。")
                                .build()));
    }
}
