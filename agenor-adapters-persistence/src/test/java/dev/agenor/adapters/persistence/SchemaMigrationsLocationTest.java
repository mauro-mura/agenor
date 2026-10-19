package dev.agenor.adapters.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The migration locations used up to 0.37.0, under {@code db/migration}, map to where the files
 * are now. Anything else passes through untouched.
 */
@DisplayName("SchemaMigrations — locations moved in 0.38.0")
class SchemaMigrationsLocationTest {

    @Test
    @DisplayName("the old directory and HITL locations map to db/agenor")
    void oldLocationsMap() {
        assertThat(SchemaMigrations.currentLocation("classpath:db/migration/agenor-directory"))
                .isEqualTo("classpath:db/agenor/directory");
        assertThat(SchemaMigrations.currentLocation("classpath:db/migration/agenor-hitl"))
                .isEqualTo("classpath:db/agenor/hitl");
    }

    @Test
    @DisplayName("without the classpath prefix, or with slashes around it, they map too")
    void oldLocationsMapInOtherSpellings() {
        assertThat(SchemaMigrations.currentLocation("db/migration/agenor-directory"))
                .isEqualTo("classpath:db/agenor/directory");
        assertThat(SchemaMigrations.currentLocation("classpath:/db/migration/agenor-hitl/"))
                .isEqualTo("classpath:db/agenor/hitl");
    }

    @Test
    @DisplayName("current and custom locations are left alone")
    void otherLocationsPassThrough() {
        assertThat(SchemaMigrations.currentLocation("classpath:db/agenor/directory"))
                .isEqualTo("classpath:db/agenor/directory");
        assertThat(SchemaMigrations.currentLocation("filesystem:/opt/migrations"))
                .isEqualTo("filesystem:/opt/migrations");
    }
}
