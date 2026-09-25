package com.openwearables.health.sdk

/**
 * One Health Connect or Samsung meal becomes the same record shape Apple uses
 * for a food correlation: a [FOOD] parent, then one child per filled nutrient.
 * A nutrient with no meal name and no meal type is a loose row (`parentId` null).
 *
 * Type names are Android vocabulary (`DIETARY_ENERGY`), not HealthKit identifiers.
 * Water stays [HYDRATION] on its own record and is not a nutrient here.
 */
internal object NutritionPayload {
    const val FOOD = "FOOD"

    /**
     * Host ids that all read the one nutrition record. The Flutter SDK asks
     * for the four dietary ids; `nutrition` / `food` are the single-type alias.
     */
    val trackedTypeIds: Set<String> = setOf(
        "nutrition",
        "food",
        "dietaryEnergyConsumed",
        "dietaryCarbohydrates",
        "dietaryProtein",
        "dietaryFatTotal",
    )

    fun isTrackedType(typeId: String): Boolean = typeId in trackedTypeIds

    data class Nutrient(
        val idSuffix: String,
        val type: String,
        val value: Double,
        val unit: String,
    )

    fun records(
        id: String,
        startDate: String,
        endDate: String,
        zoneOffset: String?,
        source: UnifiedSource,
        title: String?,
        mealType: String?,
        nutrients: List<Nutrient>,
    ): List<UnifiedRecord> {
        val cleanTitle = title?.takeIf { it.isNotBlank() }
        val cleanMeal = mealType?.takeIf { it.isNotBlank() }
        val metadata = buildMap<String, Any?> {
            if (cleanTitle != null) put("title", cleanTitle)
            if (cleanMeal != null) put("mealType", cleanMeal)
        }.ifEmpty { null }
        val filled = nutrients.filter { it.value.isFinite() }
        if (metadata == null && filled.isEmpty()) return emptyList()

        val out = ArrayList<UnifiedRecord>(filled.size + 1)
        val parentId = if (metadata != null) id else null
        if (metadata != null) {
            out += UnifiedRecord(
                id, FOOD, startDate, endDate, zoneOffset, source,
                value = 1.0, unit = null, parentId = null, metadata = metadata,
            )
        }
        for (nutrient in filled) {
            out += UnifiedRecord(
                "$id-${nutrient.idSuffix}", nutrient.type, startDate, endDate, zoneOffset, source,
                nutrient.value, nutrient.unit, parentId, null,
            )
        }
        return out
    }

    /** Samsung `MealType` name. `UNDEFINED` is not a meal. */
    fun mealTypeFromSamsung(raw: String?): String? = when (raw?.uppercase()) {
        null, "", "UNDEFINED" -> null
        else -> raw.lowercase()
    }

    fun fromSamsungFields(
        id: String,
        startDate: String,
        endDate: String,
        zoneOffset: String?,
        source: UnifiedSource,
        fields: Map<String, Any?>,
    ): List<UnifiedRecord> {
        val nutrients = SAMSUNG_NUTRIENTS.mapNotNull { spec ->
            val value = (fields[spec.field] as? Number)?.toDouble() ?: return@mapNotNull null
            Nutrient(spec.idSuffix, spec.type, value, spec.unit)
        }
        return records(
            id = id,
            startDate = startDate,
            endDate = endDate,
            zoneOffset = zoneOffset,
            source = source,
            title = fields["TITLE"] as? String,
            mealType = mealTypeFromSamsung(fields["MEAL_TYPE"] as? String),
            nutrients = nutrients,
        )
    }

    private data class SamsungNutrient(
        val field: String,
        val idSuffix: String,
        val type: String,
        val unit: String,
    )

    /** Units are the ones Samsung documents on `NutritionType`. */
    private val SAMSUNG_NUTRIENTS = listOf(
        SamsungNutrient("CALORIES", "energy", "DIETARY_ENERGY", "kcal"),
        SamsungNutrient("PROTEIN", "protein", "DIETARY_PROTEIN", "g"),
        SamsungNutrient("CARBOHYDRATE", "carbohydrate", "DIETARY_CARBOHYDRATE", "g"),
        SamsungNutrient("DIETARY_FIBER", "fiber", "DIETARY_FIBER", "g"),
        SamsungNutrient("SUGAR", "sugar", "DIETARY_SUGAR", "g"),
        SamsungNutrient("TOTAL_FAT", "total-fat", "DIETARY_TOTAL_FAT", "g"),
        SamsungNutrient("SATURATED_FAT", "saturated-fat", "DIETARY_SATURATED_FAT", "g"),
        SamsungNutrient("MONOSATURATED_FAT", "monounsaturated-fat", "DIETARY_MONOUNSATURATED_FAT", "g"),
        SamsungNutrient("POLYSATURATED_FAT", "polyunsaturated-fat", "DIETARY_POLYUNSATURATED_FAT", "g"),
        SamsungNutrient("TRANS_FAT", "trans-fat", "DIETARY_TRANS_FAT", "g"),
        SamsungNutrient("CHOLESTEROL", "cholesterol", "DIETARY_CHOLESTEROL", "mg"),
        SamsungNutrient("SODIUM", "sodium", "DIETARY_SODIUM", "mg"),
        SamsungNutrient("POTASSIUM", "potassium", "DIETARY_POTASSIUM", "mg"),
        SamsungNutrient("VITAMIN_A", "vitamin-a", "DIETARY_VITAMIN_A", "mcg"),
        SamsungNutrient("VITAMIN_C", "vitamin-c", "DIETARY_VITAMIN_C", "mg"),
        SamsungNutrient("CALCIUM", "calcium", "DIETARY_CALCIUM", "mg"),
        SamsungNutrient("IRON", "iron", "DIETARY_IRON", "mg"),
    )
}
