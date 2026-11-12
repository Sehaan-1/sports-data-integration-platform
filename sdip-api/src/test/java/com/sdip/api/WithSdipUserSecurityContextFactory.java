package com.sdip.api;

import com.sdip.domain.entity.Squad;
import com.sdip.domain.repo.SquadRepository;
import com.sdip.security.SdipPrincipal;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContextFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Builds SecurityContexts for {@link WithSdipUser}; resolves squad ids from seeded tenants. */
public class WithSdipUserSecurityContextFactory implements WithSecurityContextFactory<WithSdipUser> {

    /** Static bridge (factories are not Spring beans): published by TestContextBridge. */
    static ApplicationContext context;

    @Component
    static class TestContextBridge implements ApplicationContextAware {
        @Override
        public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
            WithSdipUserSecurityContextFactory.context = applicationContext;
        }
    }

    @Override
    public SecurityContext createSecurityContext(WithSdipUser annotation) {
        UUID squadId = null;
        if (!annotation.squad().isBlank()) {
            String name = annotation.squad().equals("A") ? "A Team" : "B Team";
            Squad squad = context.getBean(SquadRepository.class)
                    .findByTenantCodeAndName("SDIP-DEMO", name)
                    .orElseThrow(() -> new IllegalStateException("seeded squad missing: " + name));
            squadId = squad.getId();
        }
        SdipPrincipal principal = new SdipPrincipal(
                UUID.randomUUID(), annotation.email(), annotation.role(), squadId);
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.authorities()));
        return ctx;
    }
}
