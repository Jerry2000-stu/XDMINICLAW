package com.xd.xdminiclaw.rag;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 相关 Bean 配置。
 *
 * RagIndexingService 已是 @Service，由 Spring 自动扫描注册。
 * 此处仅负责声明依赖 RagIndexingService 的 Hook 和 Interceptor。
 */
@Configuration
public class RagConfig {

    @Bean
    @ConditionalOnBean(RagIndexingService.class)
    public RagAgentHook ragAgentHook(RagIndexingService ragIndexingService) {
        return new RagAgentHook(ragIndexingService);
    }

    @Bean
    @ConditionalOnBean(RagAgentHook.class)
    public RagModelInterceptor ragModelInterceptor() {
        return new RagModelInterceptor();
    }
}
