package com.healix.web;

import com.healix.common.exception.BusinessException;
import com.healix.common.exception.UnauthorizedException;
import com.healix.common.result.ApiResult;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理：将业务/校验/未授权等异常统一转换为 {@link ApiResult}。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 未登录或 Token 无效 → 401。 */
    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResult<Void> handleUnauthorized(UnauthorizedException ex) {
        return ApiResult.fail(ex.getCode(), ex.getMessage());
    }

    /** 业务异常：按 code 映射 HTTP 状态（409/401/403/404/400）。 */
    @ExceptionHandler(BusinessException.class)
    public ApiResult<Void> handleBusiness(BusinessException ex, jakarta.servlet.http.HttpServletResponse response) {
        if (ex.getCode() == 409) {
            response.setStatus(HttpStatus.CONFLICT.value());
        } else if (ex.getCode() == 401) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
        } else if (ex.getCode() == 403) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
        } else if (ex.getCode() == 404) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
        } else {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
        }
        return ApiResult.fail(ex.getCode(), ex.getMessage());
    }

    /** 请求体校验失败（@Valid / @Validated）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResult<Void> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .orElse("参数校验失败");
        return ApiResult.fail(msg);
    }

    /** 缺少必填 Query/Form 参数。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResult<Void> handleMissingParam(MissingServletRequestParameterException ex) {
        return ApiResult.fail(400, "缺少或无效参数：" + ex.getParameterName());
    }

    /** 路径/查询参数类型不匹配。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResult<Void> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ApiResult.fail(400, "参数格式错误：" + ex.getName());
    }

    /** 非法参数（通常来自业务层校验）。 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResult<Void> handleIllegalArgument(IllegalArgumentException ex) {
        return ApiResult.fail(ex.getMessage());
    }

    /**
     * SSE / 长连接：浏览器关页、刷新、切路由后写回响应会 Broken pipe。
     * 属正常断连，不应打 ERROR。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException ex) {
        log.debug("async request not usable (client gone): {}", ex.getMessage());
    }

    /**
     * SSE / DeferredResult 空闲超时（客户端仍连着但服务端未再推事件）。
     * 属预期生命周期结束，不应打 ERROR。
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public void handleAsyncRequestTimeout(AsyncRequestTimeoutException ex) {
        log.debug("async request timed out: {}", ex.getMessage());
    }

    /**
     * Security 在响应已提交（SSE 已开流）后无法再写 401/403，会包装成 ServletException。
     * 属断连/错误页连锁噪音，降级为 debug。
     */
    @ExceptionHandler(jakarta.servlet.ServletException.class)
    public void handleServletException(jakarta.servlet.ServletException ex) {
        if (isCommittedSecurityNoise(ex)) {
            log.debug("ignore security noise on committed response: {}", ex.getMessage());
            return;
        }
        if (isClientAbort(ex)) {
            log.debug("client aborted connection: {}", ex.getMessage());
            return;
        }
        log.error("Unhandled servlet error", ex);
    }

    /** Tomcat ClientAbortException 等包装为 IOException 的断连。 */
    @ExceptionHandler(IOException.class)
    public void handleIoException(IOException ex) {
        if (isClientAbort(ex)) {
            log.debug("client aborted connection: {}", ex.getMessage());
            return;
        }
        log.error("Unhandled IO error", ex);
    }

    /** 未捕获异常 → 500（日志落盘，对外统一文案）。 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResult<Void> handleOther(Exception ex) {
        if (isCommittedSecurityNoise(ex) || isClientAbort(ex)) {
            log.debug("ignore client/security noise: {}", ex.getMessage());
            return null;
        }
        log.error("Unhandled error", ex);
        return ApiResult.fail("internal error");
    }

    private static boolean isCommittedSecurityNoise(Throwable ex) {
        Throwable cur = ex;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null
                    && msg.contains("response is already committed")
                    && (msg.contains("Spring Security")
                            || cur instanceof org.springframework.security.access.AccessDeniedException)) {
                return true;
            }
            if (cur instanceof org.springframework.security.access.AccessDeniedException
                    && msg != null
                    && msg.toLowerCase().contains("access denied")) {
                // 仅当外层已说明 committed 时才吞；单纯 AccessDenied 仍应暴露
                Throwable root = ex;
                while (root != null) {
                    String m = root.getMessage();
                    if (m != null && m.contains("already committed")) {
                        return true;
                    }
                    root = root.getCause();
                }
            }
            cur = cur.getCause();
        }
        String top = ex.getMessage();
        return top != null && top.contains("Unable to handle the Spring Security Exception");
    }

    private static boolean isClientAbort(Throwable ex) {
        Throwable cur = ex;
        while (cur != null) {
            String name = cur.getClass().getName();
            if (name.contains("ClientAbortException")
                    || cur instanceof AsyncRequestNotUsableException
                    || cur instanceof AsyncRequestTimeoutException) {
                return true;
            }
            String msg = cur.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("broken pipe")
                        || lower.contains("connection reset")
                        || lower.contains("异步请求")
                        || lower.contains("not usable")
                        || lower.contains("timed out")
                        || lower.contains("timeout")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }
}
