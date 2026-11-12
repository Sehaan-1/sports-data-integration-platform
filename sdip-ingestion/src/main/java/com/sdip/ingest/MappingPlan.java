package com.sdip.ingest;

import com.sdip.domain.entity.EntityType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolved plan for interpreting one input file.
 *
 * @param entityType   target fact family
 * @param sourceSystem lineage source system for rows produced with this plan
 * @param bindings     column bindings, ordered as encountered in the file
 */
public record MappingPlan(EntityType entityType,
                          String sourceSystem,
                          List<ColumnBinding> bindings) {

    /** Pseudo-fields with special semantics (not metric codes). */
    public static final String ATHLETE_NAME = "ATHLETE_NAME";
    public static final String ATHLETE_EMAIL = "ATHLETE_EMAIL";
    public static final String ATHLETE_DOB = "ATHLETE_DOB";
    public static final String ATHLETE_EXTERNAL_ID = "ATHLETE_EXTERNAL_ID";
    public static final String RECORD_ID = "RECORD_ID";
    public static final String RECORDED_AT = "RECORDED_AT";
    public static final String SURVEY_DATE = "SURVEY_DATE";
    public static final String TEST_TYPE = "TEST_TYPE";

    /**
     * @param header   vendor header as it appears
     * @param field    canonical metric code or pseudo-field, null = column ignored
     * @param unit     declared source unit (converted to canonical unit when different)
     * @param explicit true when bound by the persisted profile, false when auto-resolved via aliases
     */
    public record ColumnBinding(String header, String field, String unit, boolean explicit) {
    }

    public Map<String, ColumnBinding> byHeader() {
        Map<String, ColumnBinding> map = new LinkedHashMap<>();
        bindings.forEach(b -> map.put(b.header(), b));
        return map;
    }

    public ColumnBinding bindingFor(String header) {
        return byHeader().get(header);
    }
}
