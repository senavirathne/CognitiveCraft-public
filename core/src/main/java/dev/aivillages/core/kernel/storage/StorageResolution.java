package dev.aivillages.core.kernel.storage;

import java.util.Objects;

/** Client-safe disposition. Only RESOLVED carries an opaque, server-created endpoint. */
public final class StorageResolution {
    public enum Status { PENDING, RESOLVED, CLARIFY, UNAVAILABLE, UNKNOWN, UNSUPPORTED }
    private final Status status;
    private final StorageResolver.Bound endpoint;
    private StorageResolution(Status status,StorageResolver.Bound endpoint) { this.status=Objects.requireNonNull(status);this.endpoint=endpoint; }
    static StorageResolution of(Status status) { if(status==Status.RESOLVED)throw new IllegalArgumentException("endpoint required");return new StorageResolution(status,null); }
    static StorageResolution resolved(StorageResolver.Bound endpoint) { return new StorageResolution(Status.RESOLVED,Objects.requireNonNull(endpoint)); }
    public Status status() { return status; }
    public StorageResolver.Bound endpoint() { return endpoint; }
    /** Shared frozen Unicode normalization for exact names; never a permission or fuzzy match. */
    public static String labelKey(String label) { return StorageLabelFold.key(StorageRegistry.label(label)); }
}
