package org.example.common;

import cn.dev33.satoken.exception.NotLoginException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理: 把异常统一转成 {@link Result}, 避免前端拿到 500 + 堆栈.
 *
 * @author ckj
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Sa-Token 未登录(拦截器校验失败) */
    @ExceptionHandler(NotLoginException.class)
    public Result<?> handleNotLogin(NotLoginException e) {
        return Result.fail("未登录或登录已过期");
    }

    /** 乐观锁冲突: 同一会话并发发消息等场景, 更新被别的请求抢先提交 */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public Result<?> handleOptimisticLock(OptimisticLockingFailureException e) {
        return Result.fail("操作冲突，请重试");
    }

    /** 非法状态: 如模型返回空内容等系统级异常, 兜底成统一信封, 避免裸 500 */
    @ExceptionHandler(IllegalStateException.class)
    public Result<?> handleIllegalState(IllegalStateException e) {
        return Result.fail("模型暂时无响应，请重试");
    }

    /** 参数/业务校验不通过(如知识库重名、不支持的文件类型), message 即业务提示 */
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<?> handleIllegalArgument(IllegalArgumentException e) {
        return Result.fail(e.getMessage());
    }
}
