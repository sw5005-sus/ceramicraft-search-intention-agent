package com.ceramicraft.search.intention.controller;

import com.ceramicraft.search.intention.service.SearchIntentService;
import com.ceramicraft.search.intention.util.PromptGuardUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;

@Tag(name = "Intent Analysis", description = "Search intent RAG + LLM analysis (SSE streaming)")
@RestController
@RequestMapping("/v1/customer/intent")
public class IntentAgentController {

    private static final Logger log = LoggerFactory.getLogger(IntentAgentController.class);

    private final SearchIntentService searchIntentService;

    public IntentAgentController(SearchIntentService searchIntentService) {
        this.searchIntentService = searchIntentService;
    }

    @Operation(summary = "Stream search intent analysis (SSE)",
            description = "Receives a natural language query and streams structured intent parsing via RAG + LLM. "
                    + "Protocol: Server-Sent Events (text/event-stream). "
                    + "Cannot be tested in Swagger UI - use curl or EventSource.")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamIntent(
            @Parameter(description = "Natural language search query", example = "premium celadon teacup")
            @RequestParam("query") String query) {

        // ===== Prompt Injection 防护 =====
        String safeQuery = PromptGuardUtils.sanitizeQuery(query);
        if (!PromptGuardUtils.isValidQuery(safeQuery)) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error")
                    .data("请输入有效的搜索内容")
                    .build());
        }
        if (PromptGuardUtils.detectRisk(safeQuery) == PromptGuardUtils.RiskLevel.BLOCKED) {
            log.warn("🛡️ 意图解析请求被拦截（Prompt Injection） — query: '{}'", query);
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error")
                    .data("搜索内容包含不允许的指令，请输入正常的商品搜索词")
                    .build());
        }

        log.info("收到意图解析请求 — query: {}", safeQuery);

        return searchIntentService.parseIntentStream(safeQuery)

                // ===== 将每个 token 包装为 SSE data 帧 =====
                .map(token -> ServerSentEvent.<String>builder()
                        .data(token)
                        .build())

                // ===== 流结束后追加 [DONE] 完成信号 =====
                .concatWith(Flux.just(
                        ServerSentEvent.<String>builder()
                                .event("complete")
                                .data("[DONE]")
                                .build()
                ))

                // ===== 全局异常降级处理 =====
                .onErrorResume(ex -> {
                    log.error("意图解析异常 — query: {}, 原因: {}", query, ex.getMessage(), ex);

                    return Flux.just(
                            ServerSentEvent.<String>builder()
                                    .event("error")
                                    .data("意图解析服务暂时不可用，请稍后重试。错误摘要: " + ex.getMessage())
                                    .build()
                    );
                })

                // ===== 超时保护：单次请求最多 60 秒 =====
                .timeout(Duration.ofSeconds(60))
                .onErrorResume(java.util.concurrent.TimeoutException.class, ex -> {
                    log.warn("意图解析超时 — query: {}", query);

                    return Flux.just(
                            ServerSentEvent.<String>builder()
                                    .event("error")
                                    .data("意图解析超时，请缩短查询内容后重试。")
                                    .build()
                    );
                });
    }
}
