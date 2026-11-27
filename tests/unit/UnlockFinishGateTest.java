package io.github.zerohomers0.onesteplite;

import java.util.List;

public final class UnlockFinishGateTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }

    public static void main(String[] args) {
        UnlockFinishGate gate = new UnlockFinishGate();
        int[] reveals = {0};
        gate.reset(7);
        long a = gate.begin(7), b = gate.begin(7);
        check(a != 0 && b != a && gate.begin(6) == 0);
        gate.afterHostFrame(7, () -> reveals[0]++);
        check(reveals[0] == 0);
        gate.committed(List.of(a));
        check(reveals[0] == 0);
        gate.committed(List.of(999L));
        check(reveals[0] == 0);
        gate.committed(List.of(b));
        check(reveals[0] == 1);
        gate.committed(List.of(a,b));
        check(reveals[0] == 1);
        gate.afterHostFrame(7, () -> reveals[0]++);
        check(reveals[0] == 2);
        gate.afterHostFrame(6, () -> reveals[0]++);
        check(reveals[0] == 2);
        a = gate.begin(7);
        gate.afterHostFrame(7, () -> reveals[0]++);
        gate.reset(7); // A rapid relock can retain exactly the same workspace epoch.
        b = gate.begin(7);
        gate.afterHostFrame(7, () -> reveals[0]++);
        gate.committed(List.of(a));
        check(reveals[0] == 2 && b != a);
        gate.committed(List.of(b));
        check(reveals[0] == 3);
        gate.reset(8);
        gate.afterHostFrame(7, () -> reveals[0]++);
        check(reveals[0] == 3);
        a=gate.begin(8);b=gate.begin(8);
        gate.afterHostFrame(8, () -> reveals[0]++);
        gate.committed(List.of(a,b));
        check(reveals[0] == 4);
        System.out.println("UnlockFinishGate checks=" + checks + " passed");
    }
}
