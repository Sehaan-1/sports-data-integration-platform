package com.sdip.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdip.domain.entity.ColumnMappingProfile;
import com.sdip.domain.entity.EntityType;
import com.sdip.domain.link.Names;
import com.sdip.domain.repo.ColumnMappingProfileRepository;
import com.sdip.domain.service.MetricDictionary;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The user-configurable column-mapping engine (§4.2). For each header in an
 * input file it produces a {@link MappingPlan.ColumnBinding} by consulting,
 * in order: (1) explicit entries in the persisted profile JSON, (2) the
 * canonical metric dictionary's alias table (e.g. "Max Vel (km/h)",
 * "Peak Speed" and "Velocity Max" all bind to GPS_MAX_VELOCITY_KMH),
 * (3) common identity-column spellings. Unresolvable columns are ignored.
 */
@Service
public class ColumnMappingService {

    private static final Logger log = LoggerFactory.getLogger(ColumnMappingService.class);

    private final ColumnMappingProfileRepository profiles;
    private final MetricDictionary dictionary;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Common header spellings for identity/temporal pseudo-fields. */
    private static final Map<String, String> IDENTITY_SPELLINGS = new LinkedHashMap<>();

    static {
        IDENTITY_SPELLINGS.put("athlete", MappingPlan.ATHLETE_NAME);
        IDENTITY_SPELLINGS.put("athlete name", MappingPlan.ATHLETE_NAME);
        IDENTITY_SPELLINGS.put("player", MappingPlan.ATHLETE_NAME);
        IDENTITY_SPELLINGS.put("player name", MappingPlan.ATHLETE_NAME);
        IDENTITY_SPELLINGS.put("name", MappingPlan.ATHLETE_NAME);
        IDENTITY_SPELLINGS.put("full name", MappingPlan.ATHLETE_NAME);
        IDENTITY_SPELLINGS.put("email", MappingPlan.ATHLETE_EMAIL);
        IDENTITY_SPELLINGS.put("e-mail", MappingPlan.ATHLETE_EMAIL);
        IDENTITY_SPELLINGS.put("athlete email", MappingPlan.ATHLETE_EMAIL);
        IDENTITY_SPELLINGS.put("dob", MappingPlan.ATHLETE_DOB);
        IDENTITY_SPELLINGS.put("date of birth", MappingPlan.ATHLETE_DOB);
        IDENTITY_SPELLINGS.put("birth date", MappingPlan.ATHLETE_DOB);
        IDENTITY_SPELLINGS.put("player id", MappingPlan.ATHLETE_EXTERNAL_ID);
        IDENTITY_SPELLINGS.put("athlete id", MappingPlan.ATHLETE_EXTERNAL_ID);
        IDENTITY_SPELLINGS.put("profile id", MappingPlan.ATHLETE_EXTERNAL_ID);
        IDENTITY_SPELLINGS.put("catapult athlete id", MappingPlan.ATHLETE_EXTERNAL_ID);
        IDENTITY_SPELLINGS.put("vald profile id", MappingPlan.ATHLETE_EXTERNAL_ID);
        IDENTITY_SPELLINGS.put("record id", MappingPlan.RECORD_ID);
        IDENTITY_SPELLINGS.put("session id", MappingPlan.RECORD_ID);
        IDENTITY_SPELLINGS.put("test id", MappingPlan.RECORD_ID);
        IDENTITY_SPELLINGS.put("date", MappingPlan.RECORDED_AT);
        IDENTITY_SPELLINGS.put("session date", MappingPlan.RECORDED_AT);
        IDENTITY_SPELLINGS.put("test date", MappingPlan.RECORDED_AT);
        IDENTITY_SPELLINGS.put("recorded at", MappingPlan.RECORDED_AT);
        IDENTITY_SPELLINGS.put("recorded utc", MappingPlan.RECORDED_AT);
        IDENTITY_SPELLINGS.put("timestamp", MappingPlan.RECORDED_AT);
        IDENTITY_SPELLINGS.put("survey date", MappingPlan.SURVEY_DATE);
        IDENTITY_SPELLINGS.put("test type", MappingPlan.TEST_TYPE);
        IDENTITY_SPELLINGS.put("test", MappingPlan.TEST_TYPE);
    }

    public ColumnMappingService(ColumnMappingProfileRepository profiles, MetricDictionary dictionary) {
        this.profiles = profiles;
        this.dictionary = dictionary;
    }

    @Transactional(readOnly = true)
    public ColumnMappingProfile requireProfile(String name) {
        return profiles.findByName(name)
                .orElseThrow(() -> new EntityNotFoundException("mapping profile '" + name + "'"));
    }

    /** Build the plan binding every header found in the file (listed in file order). */
    public MappingPlan buildPlan(ColumnMappingProfile profile, List<String> fileHeaders) {
        Map<String, ExplicitMapping> explicit = parseProfileJson(profile.getMappings());
        List<MappingPlan.ColumnBinding> bindings = new ArrayList<>();
        for (String header : fileHeaders) {
            if (header == null || header.isBlank()) {
                continue;
            }
            String normalized = Names.normalizeHeader(header);
            ExplicitMapping em = explicit.get(normalized);
            if (em != null) {
                bindings.add(new MappingPlan.ColumnBinding(header, em.field(), em.unit(), true));
                continue;
            }
            // dictionary alias resolution
            var byAlias = dictionary.resolveHeader(header);
            if (byAlias.isPresent() && byAlias.get().getCategory() == profile.getEntityType()) {
                bindings.add(new MappingPlan.ColumnBinding(header, byAlias.get().getCode(),
                        byAlias.get().getUnit(), false));
                continue;
            }
            String identity = IDENTITY_SPELLINGS.get(normalized);
            if (identity == null) {
                identity = IDENTITY_SPELLINGS.get(Names.stripUnitAnnotation(normalized));
            }
            if (identity != null) {
                bindings.add(new MappingPlan.ColumnBinding(header, identity, null, false));
                continue;
            }
            bindings.add(new MappingPlan.ColumnBinding(header, null, null, false));
            log.debug("column '{}' has no mapping in profile '{}' and no dictionary alias — ignored",
                    header, profile.getName());
        }
        return new MappingPlan(profile.getEntityType(), profile.getSourceSystem(), bindings);
    }

    @Transactional
    public ColumnMappingProfile upsertProfile(String name, EntityType entityType, String sourceSystem, String mappingsJson) {
        try {
            objectMapper.readTree(mappingsJson); // validate JSON
        } catch (Exception e) {
            throw new IllegalArgumentException("mappings must be valid JSON: " + e.getMessage());
        }
        ColumnMappingProfile profile = profiles.findByName(name)
                .orElseGet(() -> new ColumnMappingProfile(name, entityType, sourceSystem, mappingsJson));
        profile.setMappings(mappingsJson);
        profile.setSourceSystem(sourceSystem);
        profile.setEntityType(entityType);
        return profiles.save(profile);
    }

    @Transactional(readOnly = true)
    public List<ColumnMappingProfile> listProfiles() {
        return profiles.findAll();
    }

    private record ExplicitMapping(String field, String unit) {
    }

    private Map<String, ExplicitMapping> parseProfileJson(String json) {
        Map<String, ExplicitMapping> out = new LinkedHashMap<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode mappings = root.path("mappings");
            if (mappings.isArray()) {
                for (JsonNode m : mappings) {
                    String header = m.path("header").asText(null);
                    String field = m.path("field").asText(null);
                    String unit = m.path("unit").asText(null);
                    if (header != null && field != null) {
                        out.put(Names.normalizeHeader(header), new ExplicitMapping(field, unit));
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("corrupt mapping profile JSON: " + e.getMessage(), e);
        }
        return out;
    }
}
