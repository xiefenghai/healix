package com.healix.common.context;

public final class RequestContextHolder {

    private static final ThreadLocal<RequestContext> HOLDER = new ThreadLocal<>();

    private RequestContextHolder() {
    }

    public static void set(RequestContext context) {
        HOLDER.set(context);
    }

    public static RequestContext get() {
        return HOLDER.get();
    }

    public static RequestContext require() {
        RequestContext ctx = HOLDER.get();
        if (ctx == null) {
            throw new IllegalStateException("RequestContext missing");
        }
        return ctx;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
