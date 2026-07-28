package com.noop.analytics

/**
 * The catalogue of activities the user can pick for a workout — the single source of truth for the
 * sport picker (live + manual) and every sport label shown in the app.
 *
 * This used to be keyed on Health Connect's `EXERCISE_TYPE_*` constants, because the same table drove
 * the HC writeback. That integration is gone, so the ids here are now PURELY internal: nothing outside
 * this file interprets them, and nothing persists them — a workout row stores its sport as the display
 * NAME ([com.noop.data.WorkoutRow.sport]). They exist only to key [NAMES] and [DISTANCE_TYPES].
 *
 * Order matters: the picker renders [NAMES] in declaration order (common / distance sports first),
 * then the [EXTRA] sports, and always ends on the generic "Other".
 */
object SportCatalog {

    // Internal ids. Stable within the app; deliberately not tied to any external vocabulary.
    private const val RUNNING = 1
    private const val WALKING = 2
    private const val HIKING = 3
    private const val BIKING = 4
    private const val SWIMMING_OPEN_WATER = 5
    private const val ROWING = 6
    private const val RUNNING_TREADMILL = 7
    private const val BIKING_STATIONARY = 8
    private const val SWIMMING_POOL = 9
    private const val ROWING_MACHINE = 10
    private const val ELLIPTICAL = 11
    private const val STRENGTH_TRAINING = 12
    private const val WEIGHTLIFTING = 13
    private const val HIIT = 14
    private const val YOGA = 15
    private const val PILATES = 16
    private const val BOXING = 17
    private const val BASKETBALL = 18
    private const val SOCCER = 19
    private const val BASEBALL = 20
    private const val ICE_HOCKEY = 21
    private const val BADMINTON = 22
    private const val TENNIS = 23
    private const val SQUASH = 24
    private const val RACQUETBALL = 25
    private const val TABLE_TENNIS = 26
    private const val VOLLEYBALL = 27
    private const val MARTIAL_ARTS = 28
    private const val DANCING = 29
    private const val GOLF = 30
    private const val ROCK_CLIMBING = 31
    private const val STRETCHING = 32
    private const val SKIING = 33
    private const val SNOWBOARDING = 34
    private const val OTHER = 35

    /** Ordered for the picker: common / distance first, then the rest, then Other. */
    val NAMES: Map<Int, String> = linkedMapOf(
        RUNNING to "Running",
        WALKING to "Walking",
        HIKING to "Hiking",
        BIKING to "Cycling",
        SWIMMING_OPEN_WATER to "Open-water swim",
        ROWING to "Rowing",
        RUNNING_TREADMILL to "Treadmill run",
        BIKING_STATIONARY to "Indoor cycle",
        SWIMMING_POOL to "Pool swim",
        ROWING_MACHINE to "Row machine",
        ELLIPTICAL to "Elliptical",
        STRENGTH_TRAINING to "Strength",
        WEIGHTLIFTING to "Weightlifting",
        HIIT to "HIIT",
        YOGA to "Yoga",
        PILATES to "Pilates",
        BOXING to "Boxing",
        BASKETBALL to "Basketball",
        SOCCER to "Soccer",
        BASEBALL to "Baseball",
        ICE_HOCKEY to "Ice Hockey",
        BADMINTON to "Badminton",
        TENNIS to "Tennis",
        SQUASH to "Squash",
        RACQUETBALL to "Racquetball",
        TABLE_TENNIS to "Table tennis",
        VOLLEYBALL to "Volleyball",
        // Martial arts covers Jiu-Jitsu plus karate / judo / MMA etc.
        MARTIAL_ARTS to "Martial arts",
        DANCING to "Dancing",
        GOLF to "Golf",
        ROCK_CLIMBING to "Climbing",
        STRETCHING to "Stretching",
        SKIING to "Skiing",
        SNOWBOARDING to "Snowboarding",
        OTHER to "Other",
    )

    /**
     * Extra sports that share an id with a broader entry, so they can't live in the int-keyed [NAMES]
     * (they would collide). They keep their own label and are inserted just before "Other".
     */
    val EXTRA: List<Pair<String, Int>> = listOf(
        "Padel" to OTHER,
        "Pickleball" to OTHER,
        "Bowling" to OTHER,
        // An indoor treadmill walk: rides the WALKING id but keeps its own label. Kept OUT of
        // DISTANCE_TYPES so GPS defaults off (an indoor session has no route).
        "Treadmill walk" to WALKING,
        "Bodybuilding" to STRENGTH_TRAINING,
    )

    /** Sports where a route makes sense → GPS defaults on. */
    val DISTANCE_TYPES: Set<Int> = setOf(
        RUNNING, WALKING, HIKING, BIKING, SWIMMING_OPEN_WATER, ROWING,
        // Snow sports cover ground → a route makes sense.
        SKIING, SNOWBOARDING,
    )

    fun nameFor(type: Int): String = NAMES[type] ?: "Workout"
}
