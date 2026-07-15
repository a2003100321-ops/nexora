package com.nexora.storage.nfs

import com.nexora.storage.api.StorageKind
import com.nexora.storage.api.StorageModuleDescriptor

public object NfsStorageModule : StorageModuleDescriptor {
    override val kind: StorageKind = StorageKind.NFS
    override val productionReady: Boolean = false
}
