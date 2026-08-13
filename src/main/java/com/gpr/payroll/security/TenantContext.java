package com.gpr.payroll.security;

/**
 * Per-request tenant + identity context, populated by {@link JwtAuthenticationFilter} from the access
 * token's {@code companyId}, {@code super_admin} and {@code sub} claims, and read by services that scope
 * data to a company / current user. Avoids threading these through every method. Cleared per request.
 *
 * <p>Mirrors wos-hr's and wos-notification's TenantContext; all three will be lifted into wos-common
 * in a later step.
 */
public final class TenantContext {

    private record Ctx(Long companyId, boolean superAdmin, Long userId) {}

    private static final ThreadLocal<Ctx> HOLDER = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Long companyId, boolean superAdmin, Long userId) {
        HOLDER.set(new Ctx(companyId, superAdmin, userId));
    }

    /** The active tenant, or null if none selected. */
    public static Long companyId() {
        Ctx c = HOLDER.get();
        return c == null ? null : c.companyId();
    }

    /** The active tenant, throwing if absent — for endpoints that require a selected company. */
    public static Long requireCompanyId() {
        Long id = companyId();
        if (id == null) {
            throw new IllegalStateException("No company selected for this request");
        }
        return id;
    }

    public static boolean isSuperAdmin() {
        Ctx c = HOLDER.get();
        return c != null && c.superAdmin();
    }

    /** The authenticated identity id (token {@code sub}), or null on system/internal threads. */
    public static Long userId() {
        Ctx c = HOLDER.get();
        return c == null ? null : c.userId();
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** Runs {@code action} scoped to {@code companyId} so {@code @TenantId} reads/writes use it. */
    public static void runAsTenant(Long companyId, Runnable action) {
        callAsTenant(companyId, () -> {
            action.run();
            return null;
        });
    }

    /** Value-returning variant of {@link #runAsTenant}. */
    public static <T> T callAsTenant(Long companyId, java.util.function.Supplier<T> action) {
        Ctx prev = HOLDER.get();
        HOLDER.set(new Ctx(companyId, false, prev == null ? null : prev.userId()));
        try {
            return action.get();
        } finally {
            if (prev != null) {
                HOLDER.set(prev);
            } else {
                HOLDER.remove();
            }
        }
    }
}
