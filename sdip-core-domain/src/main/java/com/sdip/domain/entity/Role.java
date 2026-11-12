package com.sdip.domain.entity;

/**
 * The four RBAC roles defined in §4.5. Role names double as Spring Security
 * authorities via {@code hasRole(...)} checks.
 */
public enum Role {
    ADMIN,
    HEAD_COACH,
    PRACTITIONER,
    ANALYST
}
