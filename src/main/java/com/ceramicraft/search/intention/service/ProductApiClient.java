package com.ceramicraft.search.intention.service;

import com.ceramicraft.search.intention.model.ProductMessage;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;

/**
 * 商品 API 客户端。
 * <p>
 * 调用商品微服务 {@code GET /product-ms/v1/customer/products} 接口，
 * 支持分页拉取全量数据和按名称关键词查询单个商品。
 * </p>
 *
 * <h3>商品 API 响应格式：</h3>
 * <pre>
 * {
 *   "code": 200,
 *   "data": {
 *     "list": [
 *       { "id": 100, "name": "青花釉里红五彩凤纹尊", "category": "vases",
 *         "price": 128000, "desc": "景德镇集青花...", "stock": 1,
 *         "pic_info": "[\"phoenix_zun.png\"]", "status": 1 }
 *     ],
 *     "total": 100
 *   },
 *   "err_msg": "ok"
 * }
 * </pre>
 */
@Service
public class ProductApiClient {

    private static final Logger log = LoggerFactory.getLogger(ProductApiClient.class);

    private final WebClient webClient;
    private final String apiPath;
    private final int pageSize;

    public ProductApiClient(
            @Value("${ceramic.product-api.base-url:http://localhost:8090}") String baseUrl,
            @Value("${ceramic.product-api.path:/product-ms/v1/customer/products}") String apiPath,
            @Value("${ceramic.product-api.page-size:10}") int pageSize) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .filter(logRequest())
                .filter(logResponse())
                .build();
        this.apiPath = apiPath;
        this.pageSize = pageSize;
        log.info("✅ 商品 API 客户端初始化 — baseUrl: {}, path: {}, pageSize: {}", baseUrl, apiPath, pageSize);
    }

    /**
     * 分页拉取全部商品数据。
     * <p>
     * 利用 Reactor 的 {@code expand} 操作符递归拉取下一页，
     * 直到 offset ≥ total 或返回空列表为止。
     * </p>
     *
     * @return 所有商品的 Flux 流
     */
    public Flux<ProductMessage> fetchAllProducts() {
        log.info("开始分页拉取全部商品数据...");
        return fetchPage(0)
                .expand(page -> {
                    int nextOffset = page.offset() + page.products().size();
                    if (nextOffset >= page.total() || page.products().isEmpty()) {
                        return Mono.empty();
                    }
                    log.info("拉取下一页 — offset: {}, 已获取: {}/{}", nextOffset, nextOffset, page.total());
                    return fetchPage(nextOffset);
                })
                .flatMapIterable(PageResult::products);
    }

    /**
     * 按商品名称关键词查询商品。
     * <p>
     * 用于 Kafka 消费者收到 upload/update 事件后，从 API 拉取完整商品数据。
     * </p>
     *
     * @param name 商品名称（模糊搜索）
     * @return 匹配到的第一条商品，未找到时返回 Mono.empty()
     */
    public Mono<ProductMessage> fetchProductByName(String name) {
        log.info("按名称查询商品 — keyword: {}", name);
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(apiPath)
                        .queryParam("keyword", name)
                        .build())
                .retrieve()
                .bodyToMono(ApiResponse.class)
                .flatMap(resp -> {
                    if (resp.code() != 200 || resp.data() == null
                            || resp.data().list() == null || resp.data().list().isEmpty()) {
                        log.warn("未找到商品 — keyword: {}, code: {}, errMsg: {}",
                                name, resp.code(), resp.errMsg());
                        return Mono.empty();
                    }
                    ProductMessage product = resp.data().list().get(0);
                    log.info("查询到商品 — ID: {}, 名称: {}", product.id(), product.name());
                    return Mono.just(product);
                });
    }

    /**
     * 拉取指定偏移量的一页商品数据。
     * <p>
     * 内置 HTTP 级重试：指数退避，最多重试 3 次（2s → 4s → 8s）。
     * </p>
     */
    private Mono<PageResult> fetchPage(int offset) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(apiPath)
                        .queryParam("offset", offset)
                        .queryParam("limit", pageSize)
                        .queryParam("order_by", 0)
                        .build())
                .retrieve()
                .bodyToMono(ApiResponse.class)
                .flatMap(resp -> {
                    if (resp.code() != 200 || resp.data() == null) {
                        log.warn("📭 商品 API 返回异常 — offset: {}, code: {}, errMsg: {}",
                                offset, resp.code(), resp.errMsg());
                        return Mono.just(new PageResult(0, offset, List.of()));
                    }

                    List<ProductMessage> list = resp.data().list();
                    if (list == null) {
                        log.warn("📭 商品 API 返回 list=null — offset: {}", offset);
                        return Mono.just(new PageResult(0, offset, List.of()));
                    }

                    int total = resp.data().total();
                    log.info("📦 获取第 {} 页 — 返回 {} 条, 总计 {} 条",
                            offset / pageSize + 1, list.size(), total);
                    return Mono.just(new PageResult(total, offset, list));
                })
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                        .doBeforeRetry(signal -> log.warn("⚠️ 商品 API 请求失败，第 {} 次重试 — offset: {}, 原因: {}",
                                signal.totalRetries() + 1, offset, signal.failure().getMessage())))
                .doOnError(ex -> log.error("❌ 拉取商品 API 最终失败 — offset: {}, 错误: {}", offset, ex.getMessage()));
    }

    // ==================== HTTP 日志过滤器 ====================

    private ExchangeFilterFunction logRequest() {
        return ExchangeFilterFunction.ofRequestProcessor(request -> {
            log.debug("🔗 HTTP 请求 → {} {}", request.method(), request.url());
            return Mono.just(request);
        });
    }

    private ExchangeFilterFunction logResponse() {
        return ExchangeFilterFunction.ofResponseProcessor(response -> {
            log.debug("🔗 HTTP 响应 ← {}", response.statusCode());
            return Mono.just(response);
        });
    }

    // ==================== 内部 DTO（匹配实际 API 响应格式） ====================

    /** 内部分页结果 */
    record PageResult(int total, int offset, List<ProductMessage> products) {}

    /**
     * 商品 API 响应体。
     * <pre>{ "code": 200, "data": { "list": [...], "total": 100 }, "err_msg": "ok" }</pre>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ApiResponse(
            int code,
            ApiData data,
            @JsonProperty("err_msg") String errMsg
    ) {}

    /**
     * 响应体中的 data 部分。
     * <pre>{ "list": [ { "id":100, "name":"...", ... } ], "total": 100 }</pre>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ApiData(
            List<ProductMessage> list,
            int total
    ) {}
}

