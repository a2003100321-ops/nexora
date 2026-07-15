package com.nexora.storage.webdav

import com.nexora.storage.api.StorageKind
import com.nexora.storage.api.StorageModuleDescriptor

public object WebDavStorageModule : StorageModuleDescriptor {
    override val kind: StorageKind = StorageKind.WEBDAV
    override val productionReady: Boolean = false
}
