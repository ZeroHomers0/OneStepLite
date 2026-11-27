package io.github.zerohomers0.onesteplite;

public final class EntryGuardTest {
    public static void main(String[] args) {
        int checks = 0;
        for (int state = 0; state < 32; state++) {
            boolean interactive = (state & 1) != 0;
            boolean keyguard = (state & 2) != 0;
            boolean deviceLocked = (state & 4) != 0;
            boolean unlocked = (state & 8) != 0;
            boolean displayOn = (state & 16) != 0;
            boolean admitted = EntryGuard.allows(interactive, keyguard, deviceLocked, unlocked, displayOn);
            if (admitted != (state == 25)) {
                throw new AssertionError("Unexpected entry admission for display/keyguard/user state " + state);
            }
            checks++;
        }
        System.out.println("EntryGuard: " + checks + " checks passed");
    }
}
