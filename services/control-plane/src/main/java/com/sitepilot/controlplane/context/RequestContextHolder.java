package com.sitepilot.controlplane.context;

public final class RequestContextHolder {
    private static final ThreadLocal<RequestContext> CURRENT = new ThreadLocal<>();

    private RequestContextHolder() {}

    public static void set(RequestContext context) {
        CURRENT.set(context);
    }

    public static RequestContext get() {
        RequestContext context = CURRENT.get();
        if (context == null) {
            throw new IllegalStateException("request context unavailable");
        }
        return context;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
