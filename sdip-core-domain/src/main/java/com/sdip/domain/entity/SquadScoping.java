package com.sdip.domain.entity;

/**
 * Constants for the shared Hibernate {@code squadFilter} used for row-level
 * tenant scoping (§4.5). Every squad-scoped fact table carries a denormalised
 * {@code squad_id} column so a single uniform condition works everywhere.
 */
public final class SquadScoping {

    public static final String FILTER_NAME = "squadFilter";
    public static final String FILTER_PARAM = "squadId";
    public static final String CONDITION = "squad_id = :squadId";

    private SquadScoping() {
    }
}
