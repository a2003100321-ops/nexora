package com.nexora.storage.api

public enum class StorageKind {
    LOCAL,
    SMB,
    WEBDAV,
    NFS,
}

public interface StorageModuleDescriptor {
    public val kind: StorageKind
    public val productionReady: Boolean
}
