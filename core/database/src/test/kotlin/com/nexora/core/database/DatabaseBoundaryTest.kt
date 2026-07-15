package com.nexora.core.database

import kotlin.test.Test
import kotlin.test.assertFalse

public class DatabaseBoundaryTest {
    @Test
    public fun pickTvUserDataMigrationRemainsDisabled(): Unit {
        assertFalse(DatabaseBoundary.MIGRATES_PICKTV_USER_DATA)
    }
}
