package com.xd.xdminiclaw.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 异步线程池配置：使用JDK 21虚拟线程提升并发处理能力
 */
@Configuration
public class AsyncConfig {

    /**
     * Spring @Async 默认执行器，使用JDK 21虚拟线程
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        // JDK 21 虚拟线程执行器：轻量、高并发
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
