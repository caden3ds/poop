package com.noop.analytics


/** One selectable activity. [exerciseType] is an internal [SportCatalog] id, not an external code. */
data class Sport(val exerciseType: Int, val name: String, val isDistanceSport: Boolean)

object WorkoutSport {
    // The catalogue sports plus the EXTRA ones that share an id with a broader entry (e.g. Padel) but
    // keep their own label. The extras are inserted just before "Other" so the picker still ends on the
    // generic catch-all.
    val all: List<Sport> = buildList {
        SportCatalog.NAMES.forEach { (type, name) ->
            if (name == "Other") return@forEach // re-appended last, after the extras
            add(Sport(type, name, isDistanceSport = type in SportCatalog.DISTANCE_TYPES))
        }
        SportCatalog.EXTRA.forEach { (name, fallbackType) ->
            add(Sport(fallbackType, name, isDistanceSport = false))
        }
        SportCatalog.NAMES.entries.firstOrNull { it.value == "Other" }?.let { (type, name) ->
            add(Sport(type, name, isDistanceSport = type in SportCatalog.DISTANCE_TYPES))
        }
    }
    fun nameFor(type: Int) = SportCatalog.nameFor(type)

    /** The default when none is chosen ("Other"). */
    val default: Sport get() = all.first { it.name == "Other" }

    /** Sports where a step count is meaningful — feet on the ground — so the workout summary can show
     *  steps (#398). Deliberately narrow: outdoor + treadmill run/walk and hiking, NOT cycling/rowing/
     *  swimming (no footfalls) or gym/court sports (a step tally would be noise). Kept in lockstep with
     *  the Swift `WorkoutCatalog.onFootSportNames`. */
    val ON_FOOT_SPORTS: Set<String> = setOf("Running", "Walking", "Hiking", "Treadmill run", "Treadmill walk")

    /** Whether [sportName] (a catalogue name, possibly free-typed) is an on-foot sport that should show a
     *  step count. Case-insensitive, whitespace-trimmed. Mirrors Swift `WorkoutCatalog.isOnFoot`. */
    fun isOnFoot(sportName: String): Boolean {
        val q = sportName.trim()
        return ON_FOOT_SPORTS.any { it.equals(q, ignoreCase = true) }
    }
}
