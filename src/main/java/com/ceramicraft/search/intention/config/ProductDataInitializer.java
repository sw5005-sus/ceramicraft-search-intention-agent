package com.ceramicraft.search.intention.config;

import com.ceramicraft.search.intention.model.ProductMessage;
import com.ceramicraft.search.intention.service.ProductApiClient;
import com.ceramicraft.search.intention.service.ProductVectorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 商品数据同步器 — 启动同步 + 定时全量刷新 + 失败重试。
 * <p>
 * 功能：
 * <ul>
 *   <li><b>启动同步</b>：应用启动时自动拉取全量商品数据，打标后写入向量库</li>
 *   <li><b>失败重试</b>：首次同步失败时最多重试 N 次（可配），每次间隔递增</li>
 *   <li><b>定时同步</b>：每 24 小时（默认凌晨 3 点）自动执行全量同步</li>
 * </ul>
 * </p>
 * <p>
 * 通过 {@code ceramic.product-api.sync-on-startup=true}（默认开启）控制是否启用。
 * </p>
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "ceramic.product-api.sync-on-startup", havingValue = "true", matchIfMissing = true)
public class ProductDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductDataInitializer.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ProductApiClient apiClient;
    private final ProductVectorService vectorService;

    /** 重试最大次数 */
    @Value("${ceramic.product-api.retry.max-attempts:3}")
    private int maxRetryAttempts;

    /** 重试基础延迟（秒），实际延迟 = baseDelay × 重试次数（线性退避） */
    @Value("${ceramic.product-api.retry.delay-seconds:10}")
    private int retryDelaySeconds;

    /** 并发打标数（默认 2，受 OpenAI TPM 速率限制，建议 1~3） */
    @Value("${ceramic.product-api.sync-concurrency:2}")
    private int syncConcurrency;

    /** LLM 打标请求间隔（毫秒），用于避免触发 OpenAI TPM 限流，默认 1500ms */
    @Value("${ceramic.product-api.sync-delay-ms:1500}")
    private long syncDelayMs;

    /** 防止启动同步与定时同步并发 */
    private final AtomicBoolean syncing = new AtomicBoolean(false);

    /** 记录最近一次同步结果 */
    private volatile String lastSyncResult = "未同步";

    /** 异步同步专用线程池（单线程，守护线程，不阻塞 JVM 退出） */
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "product-sync");
        t.setDaemon(true);
        return t;
    });

    public ProductDataInitializer(ProductApiClient apiClient, ProductVectorService vectorService) {
        this.apiClient = apiClient;
        this.vectorService = vectorService;
    }

    // ==================== 启动时同步（异步，不阻塞应用启动） ====================

    @Override
    public void run(ApplicationArguments args) {
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║   商品全量数据同步 — 异步启动（不阻塞应用，可立即接收请求）    ║");
        log.info("╚══════════════════════════════════════════════════════════════╝");
        syncExecutor.submit(() -> syncWithRetry("启动同步"));
    }

    // ==================== 定时同步（每天凌晨 3 点，可通过配置覆盖） ====================

    /**
     * 定时全量同步任务。
     * <p>
     * 默认每天凌晨 3:00 执行，可通过环境变量 {@code CERAMIC_SYNC_CRON} 自定义。
     * Cron 表达式格式：秒 分 时 日 月 周
     * </p>
     */
    @Scheduled(cron = "${ceramic.product-api.sync-cron:0 0 3 * * ?}")
    public void scheduledSync() {
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║        商品全量数据同步 — 定时任务触发 ({})       ║", LocalDateTime.now().format(FMT));
        log.info("╚══════════════════════════════════════════════════════════════╝");
        syncWithRetry("定时同步");
    }

    // ==================== 核心同步逻辑（带重试） ====================

    /**
     * 执行全量同步，失败时按线性退避策略重试。
     *
     * @param trigger 触发来源描述（用于日志区分）
     */
    private void syncWithRetry(String trigger) {
        if (!syncing.compareAndSet(false, true)) {
            log.warn("⏭️ [{}] 上一次同步仍在进行中，跳过本次", trigger);
            return;
        }

        try {
            for (int attempt = 1; attempt <= maxRetryAttempts; attempt++) {
                log.info("──── [{}] 第 {}/{} 次尝试 ────", trigger, attempt, maxRetryAttempts);

                boolean success = doSync(trigger, attempt);

                if (success) {
                    return; // 同步成功，结束重试
                }

                // 最后一次不再等待
                if (attempt < maxRetryAttempts) {
                    long waitSeconds = (long) retryDelaySeconds * attempt; // 线性退避
                    log.warn("⏳ [{}] 第 {} 次同步失败，{}秒后重试...", trigger, attempt, waitSeconds);
                    try {
                        Thread.sleep(waitSeconds * 1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("⚠️ [{}] 重试等待被中断", trigger);
                        return;
                    }
                }
            }

            lastSyncResult = "❌ 失败 — " + LocalDateTime.now().format(FMT) + "（已重试 " + maxRetryAttempts + " 次）";
            log.error("╔══════════════════════════════════════════════════════════════╗");
            log.error("║  ❌ [{}] 全量同步最终失败 — 已重试 {} 次，放弃                ║", trigger, maxRetryAttempts);
            log.error("║  ⚠️  请检查商品微服务是否正常运行                              ║");
            log.error("║  📋 下次定时同步将在 24 小时后自动执行                         ║");
            log.error("╚══════════════════════════════════════════════════════════════╝");

        } finally {
            syncing.set(false);
        }
    }

    /**
     * 执行单次全量同步。
     * <p>
     * 使用 Reactor Flux 并发处理，concurrency 由 {@code ceramic.product-api.sync-concurrency} 控制。
     * 配合 Redis 打标缓存，已打标的商品会直接命中缓存，跳过 LLM 调用。
     * </p>
     *
     * @return true=成功，false=失败
     */
    private boolean doSync(String trigger, int attempt) {
        try {
            // ===== Step 1: 拉取数据 =====
            log.info("📡 [{}] Step 1/2 — 正在从商品 API 拉取全量数据...", trigger);

            List<ProductMessage> allProducts = apiClient.fetchAllProducts()
                    .collectList()
                    .block();

            if (allProducts == null || allProducts.isEmpty()) {
                log.warn("📭 [{}] 商品 API 返回空数据（0条商品）— 可能是后端未启动或数据库为空", trigger);
                return false;
            }

            log.info("📦 [{}] Step 1/2 完成 — 成功获取 {} 条商品数据", trigger, allProducts.size());

            // ===== Step 2: 逐条 LLM 打标 + 向量化入库（限速防 429） =====
            log.info("🏷️ [{}] Step 2/2 — 开始 LLM 打标 + 向量化入库（并发: {}, 间隔: {}ms）...",
                    trigger, syncConcurrency, syncDelayMs);
            log.info("💡 [{}] 提示：首次打标需调用 LLM（~2s/条），后续重启命中 Redis 缓存将大幅加速", trigger);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);
            AtomicInteger processedCount = new AtomicInteger(0);
            final int total = allProducts.size();

            Flux.fromIterable(allProducts)
                    .concatMap(product -> {
                        int idx = processedCount.incrementAndGet();
                        log.info("  [{}/{}] 处理: ID={}, 名称=「{}」, 类目={}, 价格={}元",
                                idx, total, product.id(), product.name(),
                                product.category(), product.price());

                        return vectorService.tagAndStore(product)
                                .doOnSuccess(v -> {
                                    successCount.incrementAndGet();
                                    log.info("  [{}/{}] ✅ 完成 — ID={}", idx, total, product.id());
                                })
                                .onErrorResume(ex -> {
                                    failCount.incrementAndGet();
                                    log.warn("  [{}/{}] ❌ 失败: {} — 错误: {}",
                                            idx, total, product.name(), ex.getMessage());
                                    return Mono.empty();
                                })
                                // 每条之间加延迟，防止 OpenAI TPM 限流
                                .then(Mono.delay(Duration.ofMillis(syncDelayMs)))
                                .then();
                    })
                    .then()
                    .block();

            // ===== 汇总报告 =====
            String summary = String.format("总计 %d | 成功 %d | 失败 %d",
                    total, successCount.get(), failCount.get());

            lastSyncResult = "✅ 成功 — " + LocalDateTime.now().format(FMT) + "（" + summary + "）";

            log.info("╔══════════════════════════════════════════════════════════════╗");
            log.info("║  ✅ [{}] 全量数据同步完成                                    ║", trigger);
            log.info("║  📊 {} ║", summary);
            log.info("║  🕐 完成时间: {}                               ║", LocalDateTime.now().format(FMT));
            log.info("╚══════════════════════════════════════════════════════════════╝");

            return successCount.get() > 0; // 至少有一条成功就算通过

        } catch (Exception ex) {
            log.error("💥 [{}] 第 {} 次同步异常: {}", trigger, attempt, ex.getMessage());
            return false;
        }
    }

    /**
     * 获取最近一次同步的结果描述（可供 Actuator 或健康检查使用）。
     */
    public String getLastSyncResult() {
        return lastSyncResult;
    }
}


