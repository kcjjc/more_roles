package org.example.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务配置: 给对话的【后台副作用】(摘要 / 标题生成) 提供专用线程池.
 * <p>
 * 这些任务对【本次回复】无贡献, 异步执行不阻塞主响应路径; 过载时用 {@link ThreadPoolExecutor.CallerRunsPolicy}
 * 退化为提交者线程同步执行 —— 宁可短暂拖慢也不丢任务(丢了也有下次 chat 重新投递兜底).
 *
 * @author ckj
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /** 摘要 / 标题生成的专用执行器 */
    @Bean("chatExecutor")
    public Executor chatExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("chat-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
