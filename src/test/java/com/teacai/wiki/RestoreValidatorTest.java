package com.teacai.wiki;

import com.teacai.wiki.service.RestoreValidator;
import com.teacai.wiki.service.RestoreValidator.IncompatibleBackupException;
import com.teacai.wiki.service.RestoreValidator.Migration;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RestoreValidatorTest {
    private final RestoreValidator validator = new RestoreValidator();
    private final Migration marker = new Migration(null, null, true);
    private final Migration v1 = new Migration("1", 111, true);
    private final Migration v2 = new Migration("2", 222, true);
    private final Migration v10 = new Migration("10", 1010, true);
    private final List<Migration> current = List.of(marker, v1, v2, v10);

    @Test void acceptsOnlySuccessfulOlderPrefixesOrTheExactCurrentHistory() throws Exception {
        assertTrue(validator.requiresMigration(List.of(marker, v1), current, MigrationVersion.fromVersion("10")));
        assertTrue(validator.requiresMigration(List.of(marker, v1, v2), current, MigrationVersion.fromVersion("10")));
        assertFalse(validator.requiresMigration(current, current, MigrationVersion.fromVersion("10")));
    }

    @Test void rejectsNewerVersionsUsingFlywayNumericAndDottedVersionOrdering() {
        for (String version : List.of("4", "10", "3.1")) {
            var error = assertThrows(IncompatibleBackupException.class, () -> validator.requiresMigration(
                    List.of(marker, v1, v2, new Migration(version, 123, true)),
                    List.of(marker, v1, v2, new Migration("3", 333, true)), MigrationVersion.fromVersion("3")));
            assertTrue(error.getMessage().contains("newer application database version"));
        }
        // The shipped migrations, not a potentially newer live DB history, define the supported maximum.
        assertThrows(IncompatibleBackupException.class,
                () -> validator.requiresMigration(current, current, MigrationVersion.fromVersion("3")));
    }

    @Test void rejectsEmptyFailedMissingModifiedReorderedAndUnknownMigrationHistory() {
        for (var applied : List.of(List.<Migration>of(), List.of(marker), List.of(marker, v2),
                List.of(marker, new Migration("1", 999, true)),
                List.of(marker, new Migration("1", 111, false)),
                List.of(marker, v2, v1), List.of(marker, v1, v10),
                List.of(marker, v1, new Migration("1.5", 123, true)),
                List.of(marker, new Migration("invalid", 123, true)))) {
            assertThrows(IncompatibleBackupException.class,
                    () -> validator.requiresMigration(applied, current, MigrationVersion.fromVersion("10")));
        }
    }
}
