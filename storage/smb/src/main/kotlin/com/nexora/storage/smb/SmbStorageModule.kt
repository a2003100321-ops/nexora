package com.nexora.storage.smb

import com.nexora.storage.api.StorageKind
import com.nexora.storage.api.StorageModuleDescriptor

public object SmbStorageModule : StorageModuleDescriptor {
    override val kind: StorageKind = StorageKind.SMB
    override val productionReady: Boolean = false
}
