package com.sdip.domain.service;

import java.util.UUID;

/**
 * SPI implemented by the security module: the squad restriction for the
 * current calling context.
 *
 * @return null  → no restriction (ADMIN or system/background context);
 *         UUID  → restrict all reads to that squad.
 */
public interface SquadScopeProvider {

    UUID currentSquadRestriction();
}
