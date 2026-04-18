package com.ceramicraft.search.intention.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * MLflow Tracing 集成配置 — 基于标准 OTLP 协议。
 * <p>
 * 当 {@code ceramic.mlflow.enabled=true}（默认）时生效。
 * 使用 OpenTelemetry 标准的 {@link OtlpHttpSpanExporter} 将 Trace 数据
 * 推送到 MLflow 的 OTLP 端点 {@code /v1/traces}。
 * </p>
 *
 * <h3>Trace 数据流：</h3>
 * <pre>
 * Spring AI 操作 (Chat/Embedding/VectorStore)
 *   → Micrometer Observation API（自动添加 gen_ai.usage.* 属性）
 *     → Micrometer Tracing (OTel Bridge)
 *       → OpenTelemetry SDK (BatchSpanProcessor)
 *         → OtlpHttpSpanExporter
 *           → MLflow OTLP Endpoint (POST /v1/traces)
 * </pre>
 *
 * <h3>优势（相比自定义 REST API 方案）：</h3>
 * <ul>
 *   <li>完整的 Span 树结构（父子关系、时间线、属性）原生传递给 MLflow</li>
 *   <li>gen_ai.usage.input_tokens / output_tokens 自动带入 Span，MLflow 直接解析显示</li>
 *   <li>无需手动调用 StartTrace / EndTrace / UploadSpanData — 一个请求搞定</li>
 *   <li>标准 OTLP 协议，兼容性好，无 404 问题</li>
 * </ul>
 *
 * <h3>配置项：</h3>
 * <ul>
 *   <li>{@code ceramic.mlflow.enabled}        — 是否启用 MLflow 追踪（默认 true）</li>
 *   <li>{@code ceramic.mlflow.tracking-uri}   — MLflow Tracking Server 地址</li>
 *   <li>{@code ceramic.mlflow.experiment-name} — MLflow 实验名称（自动创建）</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "ceramic.mlflow.enabled", havingValue = "true", matchIfMissing = true)
public class MlflowTracingConfig {

    private static final Logger log = LoggerFactory.getLogger(MlflowTracingConfig.class);

    /**
     * 注册 OTLP HTTP Span Exporter，指向 MLflow 的 OTLP 端点。
     * <p>
     * 通过 {@code x-mlflow-experiment-id} Header 将 Trace 路由到指定实验。
     * Spring Boot 的 OpenTelemetry 自动配置会将此 Exporter 包装在 BatchSpanProcessor 中。
     * </p>
     */
    @Bean
    public OtlpHttpSpanExporter mlflowOtlpSpanExporter(
            @Value("${ceramic.mlflow.tracking-uri:http://127.0.0.1:5000}") String trackingUri,
            @Value("${ceramic.mlflow.experiment-name:ceramicraft-search-agent}") String experimentName,
            ObjectMapper objectMapper) {

        log.info("📊 初始化 MLflow OTLP Trace Exporter — endpoint: {}/v1/traces, experiment: {}",
                trackingUri, experimentName);

        // 解析实验 ID（创建或获取已有实验）
        String experimentId = resolveExperimentId(trackingUri, experimentName, objectMapper);

        return OtlpHttpSpanExporter.builder()
                .setEndpoint(trackingUri + "/v1/traces")
                .addHeader("x-mlflow-experiment-id", experimentId)
                .build();
    }

    /**
     * 通过 MLflow REST API 获取或创建实验，返回实验 ID。
     */
    @SuppressWarnings("unchecked")
    private String resolveExperimentId(String trackingUri, String experimentName, ObjectMapper objectMapper) {
        RestClient client = RestClient.builder()
                .baseUrl(trackingUri)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();

        // 尝试获取已有实验
        try {
            Map<String, Object> response = client.get()
                    .uri("/api/2.0/mlflow/experiments/get-by-name?experiment_name={name}", experimentName)
                    .retrieve()
                    .body(Map.class);
            if (response != null && response.containsKey("experiment")) {
                Map<String, Object> exp = (Map<String, Object>) response.get("experiment");
                String id = String.valueOf(exp.get("experiment_id"));
                log.info("✅ MLflow 实验已就绪 — 名称: {}, ID: {}", experimentName, id);
                return id;
            }
        } catch (RestClientResponseException e) {
            log.debug("MLflow 实验 '{}' 不存在 (HTTP {}), 准备创建", experimentName, e.getStatusCode());
        } catch (Exception e) {
            log.warn("⚠️ 查询 MLflow 实验失败: {}", e.getMessage());
        }

        // 创建新实验
        try {
            String body = objectMapper.writeValueAsString(Map.of("name", experimentName));
            Map<String, Object> response = client.post()
                    .uri("/api/2.0/mlflow/experiments/create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (response != null && response.get("experiment_id") != null) {
                String id = String.valueOf(response.get("experiment_id"));
                log.info("✅ MLflow 实验已创建 — 名称: {}, ID: {}", experimentName, id);
                return id;
            }
        } catch (RestClientResponseException e) {
            log.warn("⚠️ MLflow 创建实验失败 — HTTP {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.warn("⚠️ 无法连接 MLflow: {}", e.getMessage());
        }

        log.info("📌 使用 MLflow 默认实验 (id=0)");
        return "0";
    }
}
