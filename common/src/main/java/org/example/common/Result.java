package org.example.common;

/**
 * 统一响应结构: { code, message, data }.
 * <ul>
 *   <li>{@link #ok(Object)} code=200 表示成功</li>
 *   <li>{@link #fail(String)} code=500 表示业务失败, message 为错误提示</li>
 * </ul>
 *
 * @author ckj
 */
public class Result<T> {

    private final int code;
    private final String message;
    private final T data;

    public Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(200, "success", data);
    }

    public static <T> Result<T> fail(String message) {
        return new Result<>(500, message, null);
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }
}
