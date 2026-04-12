package com.ceramicraft.search.intention.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

import java.net.InetSocketAddress;
import java.net.Proxy;

/**
 * HTTP 代理配置 — 同时覆盖 RestClient（Embedding）和 WebClient（Chat Streaming）。
 * <p>
 * Spring AI 内部使用两套 HTTP 客户端：
 * <ul>
 *   <li>{@link RestClient} — 用于 Embedding API（阻塞调用）</li>
 *   <li>{@link WebClient} — 用于 Chat Streaming API（Reactor Netty 响应式调用）</li>
 * </ul>
 * 两者在 WebFlux 环境下都走 Reactor Netty，均不读取 JVM 的
 * {@code -Dhttps.proxyHost} 系统属性，必须在此显式注入代理。
 * </p>
 * <p>
 * 启用方式：设置 {@code proxy.host}（通过环境变量 PROXY_HOST 或 JVM 参数 -Dhttps.proxyHost）。
 * 未设置时本配置不生效。
 * </p>
 */
@Configuration
@ConditionalOnExpression("'${proxy.host:}' != ''")
public class HttpClientConfig {

    private static final Logger log = LoggerFactory.getLogger(HttpClientConfig.class);

    /**
     * 配置 WebClient 构建器 — 用于 Chat Streaming（Reactor Netty 代理）。
     * <p>
     * Spring AI 的流式聊天接口通过 WebClient → Reactor Netty 发起请求，
     * 此处将 HTTP 代理注入 Netty 的 {@link HttpClient}。
     * </p>
     */
    @Bean
    public WebClient.Builder webClientBuilder(
            @Value("${proxy.host}") String proxyHost,
            @Value("${proxy.port:7890}") int proxyPort) {

        log.info("启用 WebClient HTTP 代理（Chat Streaming） — {}:{}", proxyHost, proxyPort);

        HttpClient httpClient = HttpClient.create()
                .proxy(proxy -> proxy
                        .type(ProxyProvider.Proxy.HTTP)
                        .host(proxyHost)
                        .port(proxyPort));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
    }

    /**
     * 配置 RestClient 构建器 — 用于 Embedding API（JDK HTTP 代理）。
     * <p>
     * 使用 JDK 原生的 {@link SimpleClientHttpRequestFactory}（基于 HttpURLConnection），
     * 替代默认的 Reactor Netty 实现，以正确支持 HTTP 代理。
     * </p>
     */
    @Bean
    public RestClient.Builder restClientBuilder(
            @Value("${proxy.host}") String proxyHost,
            @Value("${proxy.port:7890}") int proxyPort) {

        log.info("启用 RestClient HTTP 代理（Embedding） — {}:{}", proxyHost, proxyPort);

        var factory = new SimpleClientHttpRequestFactory();
        factory.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)));
        factory.setConnectTimeout(30_000);
        factory.setReadTimeout(60_000);

        return RestClient.builder().requestFactory(factory);
    }
}


