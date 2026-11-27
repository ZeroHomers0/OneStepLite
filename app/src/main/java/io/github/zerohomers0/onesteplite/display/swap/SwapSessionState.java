package io.github.zerohomers0.onesteplite;

/** Lifecycle of one verified live swap session. */
enum SwapSessionState { NEW, WAITING, RUNNING, SETTLING, FINISHED, DECLINED, CONSUMED, FAILED }
