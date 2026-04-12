package com.ceramicraft.search.intention.controller;

import com.ceramicraft.search.intention.service.SearchIntentService;
import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * 测试用 Controller — 非流式 JSON 接口，方便浏览器/Postman 直接测试。
 * <p>仅在 {@code local} Profile 下生效，生产环境不暴露。</p>
 */
@Hidden
@RestController
@RequestMapping("/api/v1/intent/test")
@org.springframework.context.annotation.Profile("local")
public class TestIntentController {

    private static final Logger log = LoggerFactory.getLogger(TestIntentController.class);
    private final SearchIntentService searchIntentService;

    public TestIntentController(SearchIntentService searchIntentService) {
        this.searchIntentService = searchIntentService;
    }

    /**
     * 完整意图解析（RAG + LLM），非流式返回。
     * 示例: GET /api/v1/intent/test?query=200元左右的景德镇青瓷茶杯
     */
    @GetMapping
    public Mono<Map<String, Object>> testParseIntent(@RequestParam("query") String query) {
        log.info("[测试] 完整意图解析 — query: {}", query);
        return searchIntentService.parseIntent(query)
                .map(result -> {
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("code", 200);
                    resp.put("query", query);
                    resp.put("intent_result", result);
                    return resp;
                })
                .onErrorResume(ex -> {
                    log.error("[测试] 意图解析失败", ex);
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("code", 500);
                    err.put("query", query);
                    err.put("error", ex.getMessage());
                    return Mono.just(err);
                });
    }

    /**
     * 仅 RAG 向量检索（不调 LLM），验证 Mock 数据是否加载正确。
     * 示例: GET /api/v1/intent/test/rag?query=茶杯
     */
    @GetMapping("/rag")
    public Mono<Map<String, Object>> testRagSearch(@RequestParam("query") String query) {
        log.info("[测试] RAG 向量检索 — query: {}", query);
        return searchIntentService.searchDocuments(query)
                .map(documents -> {
                    List<Map<String, Object>> docList = documents.stream()
                            .map(doc -> {
                                Map<String, Object> item = new LinkedHashMap<>();
                                item.put("text", doc.getText());
                                item.put("metadata", doc.getMetadata());
                                item.put("score", doc.getScore());
                                return item;
                            })
                            .toList();
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("code", 200);
                    resp.put("query", query);
                    resp.put("matched_count", docList.size());
                    resp.put("documents", docList);
                    return resp;
                })
                .onErrorResume(ex -> {
                    log.error("[测试] RAG 检索失败", ex);
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("code", 500);
                    err.put("query", query);
                    err.put("error", ex.getMessage());
                    return Mono.just(err);
                });
    }
}
