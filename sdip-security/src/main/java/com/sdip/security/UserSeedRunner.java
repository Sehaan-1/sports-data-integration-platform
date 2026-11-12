package com.sdip.security;

import com.sdip.domain.entity.AppUser;
import com.sdip.domain.entity.Role;
import com.sdip.domain.entity.Squad;
import com.sdip.domain.repo.AppUserRepository;
import com.sdip.domain.repo.SquadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Development bootstrap: seeds two demo squads and one user per role when the
 * user table is empty (gated by {@code sdip.seed.default-users=true}, default
 * true for local/dev). Production deployments must disable this and provision
 * users through their own process.
 */
@Component
@Order(0)
public class UserSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserSeedRunner.class);

    public static final String TENANT = "SDIP-DEMO";

    private final AppUserRepository users;
    private final SquadRepository squads;
    private final PasswordEncoder passwords;
    private final boolean enabled;

    public UserSeedRunner(AppUserRepository users, SquadRepository squads, PasswordEncoder passwords,
                          @Value("${sdip.seed.default-users:true}") boolean enabled) {
        this.users = users;
        this.squads = squads;
        this.passwords = passwords;
        this.enabled = enabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled || users.count() > 0) {
            return;
        }
        Squad squadA = squads.findByTenantCodeAndName(TENANT, "A Team")
                .orElseGet(() -> squads.save(new Squad("A Team", TENANT)));
        Squad squadB = squads.findByTenantCodeAndName(TENANT, "B Team")
                .orElseGet(() -> squads.save(new Squad("B Team", TENANT)));

        seed("admin@sdip.local", "Admin#123", "Platform Admin", Role.ADMIN, null);
        seed("coach@sdip.local", "Coach#123", "Head Coach (A)", Role.HEAD_COACH, squadA);
        seed("physio@sdip.local", "Physio#123", "Practitioner (A)", Role.PRACTITIONER, squadA);
        seed("analyst@sdip.local", "Analyst#123", "Analyst (B)", Role.ANALYST, squadB);
        log.warn("Seeded default development users (admin@sdip.local/Admin#123, coach@sdip.local/Coach#123, "
                + "physio@sdip.local/Physio#123, analyst@sdip.local/Analyst#123). CHANGE THESE PASSWORDS.");
    }

    private void seed(String email, String password, String name, Role role, Squad squad) {
        users.save(new AppUser(email, passwords.encode(password), name, role, squad));
    }
}
