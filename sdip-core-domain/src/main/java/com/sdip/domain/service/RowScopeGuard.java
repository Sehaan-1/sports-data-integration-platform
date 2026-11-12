package com.sdip.domain.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Row-level squad scoping (§4.5). Service entry points call
 * {@link #enableForCurrentPrincipal()} as their first statement: when the
 * current principal is squad-bound, the shared Hibernate {@code squadFilter}
 * is enabled on the transactional session for the rest of the request.
 *
 * <p>JDBC (JdbcTemplate) queries are NOT covered by Hibernate filters —
 * those paths must pass {@link #restriction()} into their SQL explicitly.</p>
 */
@Component
public class RowScopeGuard {

    @PersistenceContext
    private EntityManager entityManager;

    private final ObjectProvider<SquadScopeProvider> provider;

    public RowScopeGuard(ObjectProvider<SquadScopeProvider> provider) {
        this.provider = provider;
    }

    /** Current restriction, or null when unscoped (admin/system/no security context). */
    public UUID restriction() {
        SquadScopeProvider p = provider.getIfAvailable();
        return p != null ? p.currentSquadRestriction() : null;
    }

    /** Enable the Hibernate squad filter on the current session, if restricted. */
    public void enableForCurrentPrincipal() {
        UUID squad = restriction();
        if (squad == null) {
            return;
        }
        try {
            entityManager.unwrap(Session.class)
                    .enableFilter("squadFilter")
                    .setParameter("squadId", squad);
        } catch (IllegalStateException e) {
            // no transactional session bound yet — nothing to enable; callers must
            // rely on explicit SQL-level restriction in that case
        }
    }
}
