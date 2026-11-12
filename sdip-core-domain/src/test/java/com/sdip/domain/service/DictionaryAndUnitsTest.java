package com.sdip.domain.service;

import com.sdip.TestCoreApp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Schema-normalization backbone: aliases + units (§4.2 / §6). */
@SpringBootTest(classes = TestCoreApp.class)
class DictionaryAndUnitsTest {

    @Autowired
    MetricDictionary dictionary;

    @Test
    void vendorHeaderAliasesResolveToCanonicalMetric() {
        assertThat(dictionary.resolveHeader("Max Vel (km/h)")).hasValueSatisfying(
                d -> assertThat(d.getCode()).isEqualTo("GPS_MAX_VELOCITY_KMH"));
        assertThat(dictionary.resolveHeader("Peak Speed")).hasValueSatisfying(
                d -> assertThat(d.getCode()).isEqualTo("GPS_MAX_VELOCITY_KMH"));
        assertThat(dictionary.resolveHeader("Velocity Max")).hasValueSatisfying(
                d -> assertThat(d.getCode()).isEqualTo("GPS_MAX_VELOCITY_KMH"));
        assertThat(dictionary.resolveHeader("  player   load ")).hasValueSatisfying(
                d -> assertThat(d.getCode()).isEqualTo("GPS_PLAYER_LOAD"));
        assertThat(dictionary.resolveHeader("unheard-of column")).isEmpty();
    }

    @Test
    void unitConversions() {
        assertThat(UnitConverter.convert(36.0, "km/h", "m/s")).isEqualTo(10.0);
        assertThat(UnitConverter.convert(6.5, "km", "m")).isEqualTo(6500.0);
        assertThat(UnitConverter.convert(90, "min", "min")).isEqualTo(90);
        assertThat(UnitConverter.convert(120, "lb", "kg")).isCloseTo(54.43,
                org.assertj.core.data.Offset.offset(0.01));
        assertThatThrownBy(() -> UnitConverter.convert(1, "furlong", "m"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rangeValidationFromDictionary() {
        var kmh = dictionary.byCode("GPS_MAX_VELOCITY_KMH").orElseThrow();
        assertThat(kmh.inRange(32.5)).isTrue();
        assertThat(kmh.inRange(99.0)).isFalse(); // corrupt value → row quarantined by pipeline
    }
}
