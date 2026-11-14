package dev.aivillages.fabric.runtime.evidence;

/** Link capture data types as their native owners start. */
final class NativeCaptureSchema {
    private NativeCaptureSchema() { }

    static void link(Class<?>... types) {
        for (Class<?> type : types) {
            try {
                Class.forName(type.getName(), true, type.getClassLoader());
            } catch (ClassNotFoundException missing) {
                throw new IllegalStateException("Compiled native capture type unavailable", missing);
            }
        }
    }
}
