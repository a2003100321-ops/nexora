package com.nexora.storage.api

import kotlin.test.Test
import kotlin.test.assertEquals

public class StorageKindTest {
    @Test
    public fun firstStageContainsOnlyApprovedStorageKinds(): Unit {
        assertEquals(
            listOf(StorageKind.LOCAL, StorageKind.SMB, StorageKind.WEBDAV, StorageKind.NFS),
            StorageKind.entries,
        )
    }
}
