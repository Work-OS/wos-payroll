package com.gpr.payroll.security;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

/**
 * Discriminator multi-tenancy resolver. For every Hibernate session this scopes {@code @TenantId}
 * entities to the active company (from {@link TenantContext}). Wired in via
 * {@code spring.jpa.properties.hibernate.tenant_identifier_resolver}; Hibernate builds it with the
 * no-arg constructor, so it needs no Spring wiring. Same pattern as wos-hr and wos-notification.
 */
public class CompanyTenantResolver implements CurrentTenantIdentifierResolver<Long> {

    /** Sentinel for "no company selected" (system/internal threads) — treated as root (unfiltered). */
    public static final Long SYSTEM_TENANT = -1L;

    @Override
    public Long resolveCurrentTenantIdentifier() {
        Long id = TenantContext.companyId();
        return id != null ? id : SYSTEM_TENANT;
    }

    @Override
    public boolean isRoot(Long tenantId) {
        return SYSTEM_TENANT.equals(tenantId) || TenantContext.isSuperAdmin();
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }
}
