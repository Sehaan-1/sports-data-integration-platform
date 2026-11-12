package com.sdip.api;

import com.sdip.domain.entity.Role;
import org.springframework.security.test.context.support.WithSecurityContext;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Test annotation: run the request as an SDIP principal with the given
 * role/tenant — bypassing the HTTP login while exercising the REAL security
 * chain (filters, method security, row scoping).
 */
@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithSdipUserSecurityContextFactory.class)
public @interface WithSdipUser {

    String email() default "tester@sdip.local";

    Role role() default Role.ADMIN;

    /** Tenant-scoped squad selection: "A" or "B" (squads seeded by UserSeedRunner). "" = no squad. */
    String squad() default "";
}
