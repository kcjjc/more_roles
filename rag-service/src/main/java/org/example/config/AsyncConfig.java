package org.example.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务配置: 给 RAG 索引任务(解析/分块/向量化) 提供专用线程池.
 * <p>
 * 上传接口提交任务后立即返回, 实际索引在本线程池异步执行;
 * 过载时用 {@link ThreadPoolExecutor.CallerRunsPolicy} 退化为提交者线程同步执行, 不丢任务.
 *
 * @author ckj
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /** 索引任务专用执行器 */
    @Bean("indexTaskExecutor")
    public Executor indexTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("index-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
