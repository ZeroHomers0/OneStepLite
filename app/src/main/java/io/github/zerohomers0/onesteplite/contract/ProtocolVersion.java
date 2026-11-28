package io.github.zerohomers0.onesteplite;

/** Monotonic wire protocol version shared by every module process. */
final class ProtocolVersion {
    static final int CURRENT = 1;
    static final int MIN_SUPPORTED = 1;

    static boolean compatible(int version) {
        return version >= MIN_SUPPORTED && version <= CURRENT;
    }

    private ProtocolVersion() {}
}