package com.sdip.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdip.domain.entity.AuditEvent;
import com.sdip.domain.repo.AuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Append-only audit trail (§4.5 / RQ3). Events are written inside the
 * surrounding transaction, so an audit event exists if and only if the
 * operation it describes committed — "exactly one audit event per mutating
 * operation" is therefore testable.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public AuditEvent record(String actorEmail, String actorRole, String action,
                             String entityType, String entityId, Object details) {
        String detailsJson;
        if (details == null) {
            detailsJson = null;
        } else if (details instanceof String s) {
            detailsJson = s;
        } else if (details instanceof Map<?, ?> m) {
            detailsJson = writeJson(m);
        } else {
            detailsJson = String.valueOf(details);
        }
        AuditEvent event = new AuditEvent(actorEmail, actorRole, action, entityType, entityId, detailsJson);
        AuditEvent saved = repository.save(event);
        log.debug("audit: {} by {} ({}) on {} {}", action, actorEmail, actorRole, entityType, entityId);
        return saved;
    }

    private String writeJson(Map<?, ?> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return String.valueOf(map);
        }
    }
}
