package com.rhizodelta.infrastructure.user.service;

import com.rhizodelta.infrastructure.user.domain.PreferenceEventType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrefersAggregationPolicyTest {

    @Test
    void defaultsMatchTheDocumentedTable() {
        PrefersAggregationPolicy policy = newValidPolicy();
        policy.validate();

        assertThat(policy.halfLifeDays()).isEqualTo(30.0);
        assertThat(policy.windowHours()).isEqualTo(24L);
        assertThat(policy.weightFloor()).isEqualTo(0.0);
        assertThat(policy.weightCeiling()).isEqualTo(1000.0);

        assertThat(policy.baseWeight(PreferenceEventType.VIEW)).isEqualTo(0.5);
        assertThat(policy.baseWeight(PreferenceEventType.EXPAND)).isEqualTo(1.0);
        assertThat(policy.baseWeight(PreferenceEventType.DWELL)).isEqualTo(1.5);
        assertThat(policy.baseWeight(PreferenceEventType.LIKE)).isEqualTo(2.0);
        assertThat(policy.baseWeight(PreferenceEventType.SHARE)).isEqualTo(3.0);
    }

    @Test
    void overridingViaPropertiesUpdatesNumericKnobs() {
        PrefersAggregationPolicy policy = new PrefersAggregationPolicy(7.5, 12L, 0.1, 500.0);
        policy.validate();

        assertThat(policy.halfLifeDays()).isEqualTo(7.5);
        assertThat(policy.windowHours()).isEqualTo(12L);
        assertThat(policy.weightFloor()).isEqualTo(0.1);
        assertThat(policy.weightCeiling()).isEqualTo(500.0);
    }

    @Test
    void rejectsNegativeHalfLifeAtValidation() {
        PrefersAggregationPolicy policy = new PrefersAggregationPolicy(-1.0, 24L, 0.0, 1000.0);

        assertThatThrownBy(policy::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rhizodelta.preference.half-life-days")
                .hasMessageNotContaining("prefers-half-life-days");
    }

    @Test
    void rejectsNaNHalfLife() {
        PrefersAggregationPolicy policy = new PrefersAggregationPolicy(Double.NaN, 24L, 0.0, 1000.0);

        assertThatThrownBy(policy::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rhizodelta.preference.half-life-days")
                .hasMessageNotContaining("prefers-half-life-days");
    }

    @Test
    void rejectsZeroOrNegativeWindow() {
        PrefersAggregationPolicy policy = new PrefersAggregationPolicy(30.0, 0L, 0.0, 1000.0);

        assertThatThrownBy(policy::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window-hours");
    }

    @Test
    void rejectsCeilingNotGreaterThanFloor() {
        PrefersAggregationPolicy policy = new PrefersAggregationPolicy(30.0, 24L, 10.0, 10.0);

        assertThatThrownBy(policy::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weight-ceiling");
    }

    @Test
    void rejectsNegativeFloor() {
        PrefersAggregationPolicy policy = new PrefersAggregationPolicy(30.0, 24L, -1.0, 1000.0);

        assertThatThrownBy(policy::validate)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNaNCeiling() {
        PrefersAggregationPolicy policy = new PrefersAggregationPolicy(30.0, 24L, 0.0, Double.NaN);

        assertThatThrownBy(policy::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weight-floor/weight-ceiling");
    }

    private PrefersAggregationPolicy newValidPolicy() {
        return new PrefersAggregationPolicy(30.0, 24L, 0.0, 1000.0);
    }
}
