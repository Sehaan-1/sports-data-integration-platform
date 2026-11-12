package com.sdip.domain.service;

import com.sdip.domain.entity.MetricDefinition;
import com.sdip.domain.link.Names;
import com.sdip.domain.repo.MetricAliasRepository;
import com.sdip.domain.repo.MetricDefinitionRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory view of the canonical metric dictionary (§4.4): resolves vendor
 * headers like "Max Vel (km/h)", "Peak Speed", "Velocity Max" to the same
 * canonical {@code GPS_MAX_VELOCITY_KMH} definition.
 */
@Component
public class MetricDictionary {

    private final MetricDefinitionRepository definitions;
    private final MetricAliasRepository aliases;

    private final Map<String, MetricDefinition> byCode = new ConcurrentHashMap<>();
    private final Map<String, String> byAlias = new ConcurrentHashMap<>();

    public MetricDictionary(MetricDefinitionRepository definitions, MetricAliasRepository aliases) {
        this.definitions = definitions;
        this.aliases = aliases;
    }

    @PostConstruct
    public void refresh() {
        byCode.clear();
        byAlias.clear();
        definitions.findAll().forEach(d -> byCode.put(d.getCode(), d));
        aliases.findAll().forEach(a -> byAlias.put(a.getAlias(), a.getMetricCode()));
    }

    public Optional<MetricDefinition> byCode(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    /**
     * Resolve a vendor header to a canonical metric definition.
     * Tries the full normalized header first, then with unit annotations stripped.
     */
    public Optional<MetricDefinition> resolveHeader(String header) {
        String normalized = Names.normalizeHeader(header);
        String code = byAlias.get(normalized);
        if (code == null) {
            code = byAlias.get(Names.stripUnitAnnotation(normalized));
        }
        return code != null ? byCode(code) : Optional.empty();
    }

    public Map<String, MetricDefinition> all() {
        return Map.copyOf(byCode);
    }
}
