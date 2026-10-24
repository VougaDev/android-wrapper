package com.vougadev.shell.tracking

/** A site as the tests imagine it: positions per task, one follow address, three codes that end a task. */
val TEST_CONFIG = TrackingConfig(
    positionPath = "/api/tasks/{id}/position",
    followPath = "/api/tracking",
    endedCodes = setOf("task_over", "not_started", "not_yours"),
)
