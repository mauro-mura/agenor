package dev.agenor.adapters.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link JdbcHelper}.
 */
class JdbcHelperTest {

    private final JdbcHelper helper = new JdbcHelper(null);

    @Nested
    @DisplayName("isUniqueViolation")
    class IsUniqueViolation {

        @Test
        @DisplayName("recognises a duplicate key on PostgreSQL and H2")
        void duplicateKeyIsRecognised() {
            // Given the SQLState both PostgreSQL and H2 use for a unique violation
            var e = new SQLException("duplicate key", "23505", 23505);

            // When classified / Then it is a duplicate
            assertThat(helper.isUniqueViolation(e)).isTrue();
        }

        @Test
        @DisplayName("recognises a duplicate key on MySQL, which reports the generic 23000")
        void mysqlDuplicateKeyIsRecognised() {
            // Given MySQL's generic integrity SQLState with its duplicate-entry vendor codes
            assertThat(helper.isUniqueViolation(
                    new SQLException("Duplicate entry", "23000", 1062))).isTrue();
            assertThat(helper.isUniqueViolation(
                    new SQLException("Duplicate entry for key", "23000", 1586))).isTrue();
        }

        @Test
        @DisplayName("a NOT NULL violation is not a duplicate key")
        void notNullViolationIsNotADuplicate() {
            // Given a NOT NULL violation, which shares the 23 class but nothing else
            var e = new SQLException("column may not be null", "23502", 23502);

            // When classified / Then it must not be mistaken for an existing row.
            // Callers fall back to an UPDATE on true, so a wrong answer here makes a failed
            // INSERT look like a successful write — the row is silently never stored.
            assertThat(helper.isUniqueViolation(e)).isFalse();
        }

        @Test
        @DisplayName("a foreign-key violation is not a duplicate key")
        void foreignKeyViolationIsNotADuplicate() {
            assertThat(helper.isUniqueViolation(
                    new SQLException("referential integrity", "23503", 23503))).isFalse();
        }

        @Test
        @DisplayName("a MySQL integrity error that is not a duplicate is not a duplicate")
        void mysqlNonDuplicateIntegrityErrorIsNotADuplicate() {
            // 1048 ER_BAD_NULL_ERROR shares SQLState 23000 with duplicate entries
            assertThat(helper.isUniqueViolation(
                    new SQLException("Column cannot be null", "23000", 1048))).isFalse();
        }

        @Test
        @DisplayName("a null SQLState is not a duplicate key")
        void nullSqlStateIsNotADuplicate() {
            assertThat(helper.isUniqueViolation(new SQLException("driver said nothing"))).isFalse();
        }
    }
}
