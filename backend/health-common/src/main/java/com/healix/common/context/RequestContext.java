package com.healix.common.context;

import java.util.Collections;
import java.util.Set;

public class RequestContext {

    private String portal;
    private String accountId;
    private String patientId;
    private String staffId;
    private String homeTenantId;
    private String tenantId;
    private String currentOrgId;
    private Set<String> roles = Collections.emptySet();

    public String getPortal() {
        return portal;
    }

    public void setPortal(String portal) {
        this.portal = portal;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getPatientId() {
        return patientId;
    }

    public void setPatientId(String patientId) {
        this.patientId = patientId;
    }

    public String getStaffId() {
        return staffId;
    }

    public void setStaffId(String staffId) {
        this.staffId = staffId;
    }

    public String getHomeTenantId() {
        return homeTenantId;
    }

    public void setHomeTenantId(String homeTenantId) {
        this.homeTenantId = homeTenantId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getCurrentOrgId() {
        return currentOrgId;
    }

    public void setCurrentOrgId(String currentOrgId) {
        this.currentOrgId = currentOrgId;
    }

    public Set<String> getRoles() {
        return roles;
    }

    public void setRoles(Set<String> roles) {
        this.roles = roles == null ? Collections.emptySet() : roles;
    }
}
