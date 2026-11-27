package io.github.zerohomers0.onesteplite;

/** Restorable native layer metadata for one exact Host overlay surface. */
final class NativeHostLayer {
    final Object surface;
    int normalLayer;
    Object normalRelativeTo;
    Object nativeAnchor;
    int nativeLayer;

    NativeHostLayer(Object surface) { this.surface = surface; }
}
