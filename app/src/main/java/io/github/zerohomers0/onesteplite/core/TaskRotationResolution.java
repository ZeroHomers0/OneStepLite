package io.github.zerohomers0.onesteplite;

/** One resolved task-rotation decision shared by the WM hooks. */
final class TaskRotationResolution {
    final int overrideOrientation, resolvedRotation, taskRotation;
    final boolean rotationLocked;

    TaskRotationResolution(int overrideOrientation, int resolvedRotation, int taskRotation,
            boolean rotationLocked) {
        this.overrideOrientation = overrideOrientation;
        this.resolvedRotation = resolvedRotation;
        this.taskRotation = taskRotation;
        this.rotationLocked = rotationLocked;
    }

    boolean lands() {
        return taskRotation == 1 || taskRotation == 3;
    }
}
