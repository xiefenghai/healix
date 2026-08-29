package com.healix.agent.safety.chain;

public abstract class SafetyHandler {

    private SafetyHandler next;

    public void setNext(SafetyHandler next) {
        this.next = next;
    }

    public final String handle(SafetyContext context) {
        doHandle(context);
        if (next != null) {
            return next.handle(context);
        }
        return context.getReply();
    }

    protected abstract void doHandle(SafetyContext context);
}
