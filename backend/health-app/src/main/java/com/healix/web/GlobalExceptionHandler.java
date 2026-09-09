package com.healix.web;

import com.healix.common.exception.BusinessException;
import com.healix.common.exception.UnauthorizedException;
import com.healix.common.result.ApiResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
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

    /** 未捕获异常 → 500（日志落盘，对外统一文案）。 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResult<Void> handleOther(Exception ex) {
        log.error("Unhandled error", ex);
        return ApiResult.fail("internal error");
    }
}
