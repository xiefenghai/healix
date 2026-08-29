package com.healix.agent.safety.chain;

public class SafetyContext {

    private final String tenantId;
    private final String patientId;
    private String reply;

    public SafetyContext(String tenantId, String patientId, String reply) {
        this.tenantId = tenantId;
        this.patientId = patientId;
        this.reply = reply;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getPatientId() {
        return patientId;
    }

    public String getReply() {
        return reply;
    }

    public void setReply(String reply) {
        this.reply = reply;
    }
}
