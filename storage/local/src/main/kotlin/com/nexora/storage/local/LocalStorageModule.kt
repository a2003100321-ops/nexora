package com.nexora.storage.local

import com.nexora.storage.api.StorageKind
import com.nexora.storage.api.StorageModuleDescriptor

public object LocalStorageModule : StorageModuleDescriptor {
    override val kind: StorageKind = StorageKind.LOCAL
    override val productionReady: Boolean = false
}
