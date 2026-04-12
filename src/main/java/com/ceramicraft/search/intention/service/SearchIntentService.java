package com.ceramicraft.search.intention.service;

import com.ceramicraft.search.intention.config.PromptConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 搜索意图解析服务。
 * <p>
 * 核心流程（RAG + Streaming LLM）：
 * <ol>
 *   <li>从 Redis 向量库中检索与用户查询语义相近的陶瓷领域知识/标签</li>
 *   <li>将检索到的领域知识注入 System Prompt，构造上下文增强的提示词</li>
 *   <li>调用大模型（通过 MLflow 网关）以流式方式输出结构化意图解析结果</li>
 * </ol>
 * 所有 I/O 均为响应式：向量检索通过 {@code boundedElastic} 调度器桥接，
 * 大模型调用使用 Spring AI 原生的 {@link Flux} 流式 API。
 * </p>
 */
@Service
public class SearchIntentService {

    private static final Logger log = LoggerFactory.getLogger(SearchIntentService.class);

    /** Spring AI ChatClient — 流式对话的统一入口 */
    private final ChatClient chatClient;

    /** Redis 向量存储 — RAG 检索入口 */
    private final VectorStore vectorStore;

    /** 外部化的提示词配置 */
    private final PromptConfig promptConfig;

    /**
     * 构造方法注入。
     *
     * @param chatClientBuilder Spring AI 自动注入的 ChatClient 构建器
     * @param vectorStore       Redis 向量存储实例
     * @param promptConfig      可外部化的提示词配置
     */
    public SearchIntentService(ChatClient.Builder chatClientBuilder,
                               @Lazy VectorStore vectorStore,
                               PromptConfig promptConfig) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.promptConfig = promptConfig;
    }

    /**
     * 以非流式方式解析用户的搜索意图（测试用）。
     * <p>
     * 内部复用流式方法，将所有 token 拼接为完整的 JSON 字符串返回。
     * 适用于测试场景，无需 SSE 客户端即可查看完整结果。
     * </p>
     *
     * @param userQuery 用户的自然语言搜索输入
     * @return 完整的意图解析结果（JSON 字符串）
     */
    public Mono<String> parseIntent(String userQuery) {
        return parseIntentStream(userQuery)
                .reduce("", String::concat);
    }

    /**
     * 仅执行 RAG 向量检索，不调用大模型（调试用）。
     * <p>
     * 用于验证 Mock 数据是否正确写入向量库，以及检索结果是否符合预期。
     * </p>
     *
     * @param userQuery 用户查询
     * @return 检索到的文档列表（JSON 格式）
     */
    public Mono<List<Document>> searchDocuments(String userQuery) {
        return Mono.fromCallable(() -> {
                    var searchRequest = SearchRequest.builder()
                            .query(userQuery)
                            .topK(promptConfig.ragTopK())
                            .similarityThreshold(promptConfig.similarityThreshold())
                            .build();
                    return vectorStore.similaritySearch(searchRequest);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 以流式方式解析用户的搜索意图。
     * <p>
     * 该方法是完全响应式的：
     * <ul>
     *   <li>Step 1：将阻塞的向量检索调度到 {@code boundedElastic} 线程池</li>
     *   <li>Step 2：在事件循环线程中组装增强 Prompt</li>
     *   <li>Step 3：返回大模型的流式 token 输出</li>
     * </ul>
     * </p>
     *
     * @param userQuery 用户的自然语言搜索输入，例如 "200元左右的景德镇青瓷茶杯"
     * @return 大模型流式输出的 token 流，每个元素是一个文本片段
     */
    public Flux<String> parseIntentStream(String userQuery) {
        log.info("开始解析搜索意图 — 用户查询: {}", userQuery);

        // ========== Step 1: RAG 向量检索（桥接阻塞调用到弹性线程池） ==========
        return retrieveDomainKnowledge(userQuery)
                .flatMapMany(domainKnowledge -> {

                    // ========== Step 2: 组装增强的 System Prompt ==========
                    var systemPrompt = buildSystemPrompt(domainKnowledge);

                    log.debug("System Prompt 已组装，领域知识片段长度: {} 字符", domainKnowledge.length());

                    // ========== Step 3: 流式调用大模型 ==========
                    return chatClient.prompt()
                            .system(systemPrompt)
                            .user(userQuery)
                            .stream()
                            .content();
                });
    }

    /**
     * 从 Redis 向量库中检索与查询相关的陶瓷领域知识。
     * <p>
     * 由于 {@link VectorStore#similaritySearch} 目前为阻塞式 API，
     * 使用 {@code Mono.fromCallable} + {@code Schedulers.boundedElastic()}
     * 将其桥接为响应式调用，避免阻塞 Netty 事件循环线程。
     * </p>
     *
     * @param userQuery 用户查询
     * @return 拼接后的领域知识文本
     */
    private Mono<String> retrieveDomainKnowledge(String userQuery) {
        return Mono.fromCallable(() -> {
                    // 构建相似度搜索请求
                    var searchRequest = SearchRequest.builder()
                            .query(userQuery)
                            .topK(promptConfig.ragTopK())
                            .similarityThreshold(promptConfig.similarityThreshold())
                            .build();

                    List<Document> documents = vectorStore.similaritySearch(searchRequest);

                    log.info("RAG 检索完成 — 命中文档数: {}", documents.size());

                    // 将检索到的文档内容拼接为领域知识上下文
                    return documents.stream()
                            .map(Document::getText)
                            .collect(Collectors.joining("\n---\n"));
                })
                .subscribeOn(Schedulers.boundedElastic())   // 在弹性线程池执行阻塞检索
                .defaultIfEmpty(promptConfig.noDomainKnowledgeText());  // 兜底：无检索结果时的默认文本
    }

    /**
     * 使用领域知识填充 System Prompt 模板。
     *
     * @param domainKnowledge RAG 检索到的领域知识文本
     * @return 完整的 System Prompt
     */
    private String buildSystemPrompt(String domainKnowledge) {
        return promptConfig.effectiveSystemPrompt().formatted(domainKnowledge)
                + promptConfig.languageDirective();
    }
}

