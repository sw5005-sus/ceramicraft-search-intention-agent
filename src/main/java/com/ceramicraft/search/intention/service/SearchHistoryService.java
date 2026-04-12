package com.ceramicraft.search.intention.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 搜索历史与热搜服务。
 * <p>
 * 基于 Redis Sorted Set 实现：
 * <ul>
 *   <li>用户搜索历史：{@code ceramic:search-history:{userId}} — score=时间戳</li>
 *   <li>全站热搜：{@code ceramic:search-hot} — score=搜索次数</li>
 * </ul>
 */
@Service
public class SearchHistoryService {

    private static final Logger log = LoggerFactory.getLogger(SearchHistoryService.class);
    private static final String HISTORY_PREFIX = "ceramic:search-history:";
    private static final String HOT_KEY = "ceramic:search-hot";
    private static final String DOC_MAPPING_PREFIX = "ceramic:doc-mapping:";
    private static final long MAX_HISTORY_SIZE = 50;
    private static final Duration HISTORY_TTL = Duration.ofDays(30);
    private static final String DEDUP_PREFIX = "ceramic:search-dedup:";
    private static final Duration DEDUP_TTL = Duration.ofSeconds(5);

    private final ReactiveStringRedisTemplate redis;

    public SearchHistoryService(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 记录一次搜索行为（仅登录用户，含 5 秒去重）。
     * <p>
     * 同时更新用户搜索历史和全站热搜计数。
     * 匿名用户不记录任何数据。
     * </p>
     */
    public Mono<Void> record(String userId, String query) {
        if (userId == null || userId.isBlank() || query == null || query.isBlank()) {
            return Mono.empty();
        }
        String q = query.strip().toLowerCase();
        String dedupKey = DEDUP_PREFIX + userId + ":" + q;

        return redis.opsForValue().setIfAbsent(dedupKey, "1", DEDUP_TTL)
                .flatMap(isNew -> {
                    if (!Boolean.TRUE.equals(isNew)) {
                        log.debug("搜索去重 — 用户: {}, 查询: '{}' (5s 内重复)", userId, q);
                        return Mono.empty();
                    }
                    String historyKey = HISTORY_PREFIX + userId;
                    double ts = System.currentTimeMillis();
                    return Mono.when(
                            redis.opsForZSet().add(historyKey, q, ts)
                                    .then(trimHistory(historyKey))
                                    .then(redis.expire(historyKey, HISTORY_TTL))
                                    .then(),
                            redis.opsForZSet().incrementScore(HOT_KEY, q, 1).then()
                    );
                })
                .then()
                .doOnSuccess(v -> log.debug("搜索记录完成 — 用户: {}, 查询: '{}'", userId, q));
    }

    /** 裁剪历史：保留最近 MAX_HISTORY_SIZE 条（score 最大的） */
    private Mono<Void> trimHistory(String key) {
        return redis.opsForZSet().size(key)
                .filter(size -> size > MAX_HISTORY_SIZE)
                .flatMap(size -> {
                    long removeUpTo = size - MAX_HISTORY_SIZE - 1;
                    return redis.opsForZSet()
                            .removeRange(key, Range.closed(0L, removeUpTo));
                })
                .then();
    }

    /**
     * 获取用户最近的搜索历史（按时间倒序）。
     */
    public Mono<List<String>> getHistory(String userId, int limit) {
        return redis.opsForZSet()
                .reverseRange(HISTORY_PREFIX + userId, Range.closed(0L, (long) (limit - 1)))
                .collectList();
    }

    /**
     * 清空用户的搜索历史。
     */
    public Mono<Void> clearHistory(String userId) {
        return redis.delete(HISTORY_PREFIX + userId)
                .then()
                .doOnSuccess(v -> log.info("搜索历史已清空 — 用户: {}", userId));
    }

    /**
     * 获取全站热搜排行榜（按搜索次数降序）。
     */
    public Mono<List<String>> getHotSearches(int limit) {
        return redis.opsForZSet()
                .reverseRange(HOT_KEY, Range.closed(0L, (long) (limit - 1)))
                .collectList();
    }

    /**
     * 从 Redis 中随机获取商品名称（用于匿名用户的默认推荐）。
     * <p>
     * 扫描 {@code ceramic:doc-mapping:*} 键，提取商品名称并随机打乱。
     * </p>
     *
     * @param limit 返回数量
     * @return 随机商品名称列表
     */
    public Mono<List<String>> getRandomProductNames(int limit) {
        return redis.scan(ScanOptions.scanOptions()
                        .match(DOC_MAPPING_PREFIX + "*")
                        .count(200)
                        .build())
                .map(key -> key.substring(DOC_MAPPING_PREFIX.length()))
                .collectList()
                .map(names -> {
                    if (names.isEmpty()) {
                        return List.<String>of();
                    }
                    var mutable = new ArrayList<>(names);
                    Collections.shuffle(mutable);
                    return mutable.stream().limit(limit).toList();
                })
                .doOnNext(list -> log.debug("随机商品推荐 — 取到 {} 条", list.size()));
    }

    /**
     * 猜你想搜 — 用户最近 3 条历史 + 全站热搜，去重混合。
     * 冷启动时从向量库商品中随机推荐。
     */
    public Mono<List<String>> getSuggestions(String userId, int limit) {
        Mono<List<String>> historyMono = (userId != null && !userId.isBlank())
                ? getHistory(userId, 3)
                : Mono.just(List.of());
        Mono<List<String>> hotMono = getHotSearches(limit);

        return Mono.zip(historyMono, hotMono)
                .flatMap(tuple -> {
                    var merged = new java.util.LinkedHashSet<>(tuple.getT1());
                    merged.addAll(tuple.getT2());

                    if (merged.isEmpty()) {
                        log.info("猜你想搜 — 冷启动，从商品库随机推荐");
                        return getRandomProductNames(limit);
                    }
                    return Mono.just(merged.stream().limit(limit).toList());
                });
    }
}

