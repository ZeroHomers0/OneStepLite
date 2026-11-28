package io.github.zerohomers0.onesteplite;

/** Stable module-provider identity used by content hooks and the module app. */
final class ContentProtocol {
    static final String MODULE = "io.github.zerohomers0.onesteplite";
    static final String AUTHORITY = MODULE + ".content";
    static final String ROOT_URI = "content://" + AUTHORITY;

    private ContentProtocol() {}
}