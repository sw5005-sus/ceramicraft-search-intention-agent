package com.ceramicraft.search.intention;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 陶瓷电商搜索意图 Agent 启动类。
 * <p>
 * 本服务作为多 Agent 编排系统中的「搜索意图 Agent」，
 * 专门用于解析陶瓷电商场景下用户的自然语言搜索意图，
 * 并通过 SSE 流式接口将结构化意图返回给上游 Orchestrator。
 * </p>
 */
@SpringBootApplication
public class CeramicraftSearchIntentionAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CeramicraftSearchIntentionAgentApplication.class, args);
    }
}

