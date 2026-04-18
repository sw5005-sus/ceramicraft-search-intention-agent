package com.ceramicraft.search.intention.controller;

import com.ceramicraft.search.intention.service.ProductTaggingService;
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

import java.util.LinkedHashMap;
import java.util.Map;

@Tag(name = "Product Tagging", description = "AI-powered product auto-tagging (non-streaming and SSE streaming)")
@RestController
@RequestMapping("/v1/customer/tagging")
public class ProductTaggingController {

    private static final Logger log = LoggerFactory.getLogger(ProductTaggingController.class);

    private final ProductTaggingService taggingService;

    public ProductTaggingController(ProductTaggingService taggingService) {
        this.taggingService = taggingService;
    }

    @Operation(summary = "Auto-tag a product (non-streaming)",
            description = "Submit product info, LLM returns structured tags JSON.")
    @PostMapping
    public Mono<Map<String, Object>> tagProduct(@RequestBody TaggingRequest request) {
        log.info("收到商品打标请求 — 商品: {}", request.name());

        // ===== Prompt Injection 防护 =====
        String combinedInput = String.join(" ",
                request.name() != null ? request.name() : "",
                request.category() != null ? request.category() : "",
                request.desc() != null ? request.desc() : "");
        if (PromptGuardUtils.detectRisk(combinedInput) == PromptGuardUtils.RiskLevel.BLOCKED) {
            log.warn("🛡️ 打标请求被拦截（Prompt Injection） — 商品: {}", request.name());
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("code", 403);
            err.put("product_name", request.name());
            err.put("error", "商品信息中包含不允许的指令内容");
            return Mono.just(err);
        }

        // 清洗输入
        String safeName = PromptGuardUtils.sanitizeText(request.name(), PromptGuardUtils.MAX_NAME_LENGTH);
        String safeCategory = PromptGuardUtils.sanitizeText(request.category(), PromptGuardUtils.MAX_NAME_LENGTH);
        String safeDesc = PromptGuardUtils.sanitizeText(request.desc(), PromptGuardUtils.MAX_DESCRIPTION_LENGTH);

        return taggingService.tagProduct(safeName, safeCategory, request.price(), safeDesc)
                .map(result -> {
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("code", 200);
                    resp.put("product_name", request.name());
                    resp.put("tags_result", result);
                    return resp;
                })
                .onErrorResume(ex -> {
                    log.error("商品打标失败 — 商品: {}", request.name(), ex);
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("code", 500);
                    err.put("product_name", request.name());
                    err.put("error", ex.getMessage());
                    return Mono.just(err);
                });
    }

    @Operation(summary = "Auto-tag a product (SSE streaming)",
            description = "LLM tagging streamed token-by-token via SSE. Cannot be tested in Swagger UI - use curl.")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> tagProductStream(
            @Parameter(description = "Product name", required = true, example = "Celadon teacup")
            @RequestParam("name") String name,
            @Parameter(description = "Product category", example = "teaware")
            @RequestParam(value = "category", defaultValue = "") String category,
            @Parameter(description = "Product price", example = "128")
            @RequestParam(value = "price", defaultValue = "0") int price,
            @Parameter(description = "Product description", example = "Hand-thrown celadon teacup")
            @RequestParam(value = "desc", defaultValue = "") String desc) {

        log.info("收到流式打标请求 — 商品: {}", name);

        // ===== Prompt Injection 防护 =====
        String combinedInput = String.join(" ", name, category, desc);
        if (PromptGuardUtils.detectRisk(combinedInput) == PromptGuardUtils.RiskLevel.BLOCKED) {
            log.warn("🛡️ 流式打标请求被拦截 — 商品: {}", name);
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error")
                    .data("商品信息中包含不允许的指令内容")
                    .build());
        }

        // 清洗输入
        String safeName = PromptGuardUtils.sanitizeText(name, PromptGuardUtils.MAX_NAME_LENGTH);
        String safeCategory = PromptGuardUtils.sanitizeText(category, PromptGuardUtils.MAX_NAME_LENGTH);
        String safeDesc = PromptGuardUtils.sanitizeText(desc, PromptGuardUtils.MAX_DESCRIPTION_LENGTH);

        return taggingService.tagProductStream(safeName, safeCategory, price, safeDesc)
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
                    log.error("流式打标异常 — 商品: {}", name, ex);
                    return Flux.just(
                            ServerSentEvent.<String>builder()
                                    .event("error")
                                    .data("打标服务暂时不可用: " + ex.getMessage())
                                    .build()
                    );
                });
    }

    public record TaggingRequest(
            String name,
            String category,
            int price,
            String desc
    ) {}
}
