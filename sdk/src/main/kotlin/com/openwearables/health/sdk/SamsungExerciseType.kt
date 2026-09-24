package com.openwearables.health.sdk

/**
 * Samsung Health stores a workout's activity as a predefined integer
 * (`11007` = cycling). The payload uses the same names Health Connect
 * already sends, so the backend can classify the session.
 */
internal object SamsungExerciseType {

    fun toPayloadType(raw: Any?): String = when (raw) {
        null -> "OTHER"
        is Enum<*> -> normalizeName(raw.name)
        is Number -> fromCode(raw.toInt())
        is String -> raw.trim().toIntOrNull()?.let { fromCode(it) } ?: normalizeName(raw.trim())
        else -> {
            val text = raw.toString().trim()
            text.toIntOrNull()?.let { fromCode(it) } ?: normalizeName(text)
        }
    }

    private fun fromCode(code: Int): String = CODES[code] ?: "OTHER"

    private fun normalizeName(name: String): String {
        if (name.isEmpty() || name.equals("UNKNOWN", ignoreCase = true)) return "OTHER"
        return NAME_ALIASES[name.uppercase()] ?: name.uppercase()
    }

    private val CODES = mapOf(
        0 to "OTHER",
        1001 to "WALKING",
        1002 to "RUNNING",
        2001 to "BASEBALL",
        2002 to "SOFTBALL",
        2003 to "CRICKET",
        3001 to "GOLF",
        4002 to "RUGBY",
        4003 to "BASKETBALL",
        4004 to "SOCCER",
        4005 to "HANDBALL",
        4006 to "FOOTBALL_AMERICAN",
        5001 to "VOLLEYBALL",
        5002 to "VOLLEYBALL",
        6001 to "SQUASH",
        6002 to "TENNIS",
        6003 to "BADMINTON",
        6004 to "TABLE_TENNIS",
        6005 to "RACQUETBALL",
        7001 to "MARTIAL_ARTS",
        7002 to "BOXING",
        7003 to "MARTIAL_ARTS",
        8002 to "DANCING",
        8003 to "DANCING",
        9001 to "PILATES",
        9002 to "YOGA",
        10001 to "STRETCHING",
        10007 to "HIIT",
        11001 to "SKATING",
        11002 to "PARAGLIDING",
        11007 to "CYCLING",
        11008 to "FRISBEE_DISC",
        11009 to "SKATING",
        13001 to "HIKING",
        13002 to "ROCK_CLIMBING",
        13004 to "CYCLING",
        14001 to "SWIMMING_POOL",
        14003 to "PADDLING",
        14004 to "SAILING",
        14005 to "SCUBA_DIVING",
        14007 to "PADDLING",
        14010 to "ROWING_MACHINE",
        15001 to "STAIR_CLIMBING_MACHINE",
        15002 to "WEIGHTLIFTING",
        15003 to "CYCLING_STATIONARY",
        15004 to "ROWING_MACHINE",
        15005 to "RUNNING_TREADMILL",
        15006 to "ELLIPTICAL",
        16001 to "SKIING",
        16002 to "SKIING",
        16004 to "ICE_SKATING",
        16006 to "ICE_HOCKEY",
        16007 to "SNOWBOARDING",
        16008 to "SKIING",
        16009 to "SNOWSHOEING",
    )

    private val NAME_ALIASES = mapOf(
        "BIKING" to "CYCLING",
        "MOUNTAIN_BIKING" to "CYCLING",
        "STATIONARY_BIKING" to "CYCLING_STATIONARY",
        "POOL_SWIMMING" to "SWIMMING_POOL",
        "OPEN_WATER_SWIMMING" to "SWIMMING_OPEN_WATER",
        "AMERICAN_FOOTBALL" to "FOOTBALL_AMERICAN",
        "TRACK_RUNNING" to "RUNNING",
        "TREADMILL" to "RUNNING_TREADMILL",
        "WEIGHT_MACHINE" to "WEIGHTLIFTING",
        "CANOEING" to "PADDLING",
        "KAYAKING" to "PADDLING",
        "FLYING_DISC" to "FRISBEE_DISC",
        "INLINE_SKATING" to "SKATING",
        "ROLLER_SKATING" to "SKATING",
        "BALLROOM_DANCING" to "DANCING",
        "ZUMBA" to "DANCING",
        "CROSS_COUNTRY_SKIING" to "SKIING",
        "ALPINE_SKIING" to "SKIING",
        "STEP_MACHINE" to "STAIR_CLIMBING_MACHINE",
        "CIRCUIT_TRAINING" to "HIIT",
        "UNDEFINED" to "OTHER",
        "OTHER" to "OTHER",
        "UNKNOWN" to "OTHER",
        "CUSTOM" to "OTHER",
    )
}
