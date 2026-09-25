package com.openwearables.health.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NutritionPayloadTest {
    private val source = UnifiedSource(
        appId = "com.myfitnesspal.android",
        deviceId = null,
        deviceName = null,
        deviceManufacturer = null,
        deviceModel = null,
        deviceType = null,
        recordingMethod = null,
    )

    @Test
    fun mealBecomesFoodParentAndNutrientChildren() {
        val records = NutritionPayload.fromSamsungFields(
            id = "samsung-meal-1",
            startDate = "2026-09-18T12:00:00Z",
            endDate = "2026-09-18T12:00:00Z",
            zoneOffset = "+02:00",
            source = source,
            fields = mapOf(
                "TITLE" to "Chicken with rice",
                "MEAL_TYPE" to "DINNER",
                "CALORIES" to 550f,
                "PROTEIN" to 38.2f,
                "POLYSATURATED_FAT" to 2.1f,
            ),
        )

        assertEquals("FOOD", records[0].type)
        assertEquals(1.0, records[0].value)
        assertNull(records[0].unit)
        assertNull(records[0].parentId)
        assertEquals("Chicken with rice", records[0].metadata?.get("title"))
        assertEquals("dinner", records[0].metadata?.get("mealType"))

        val energy = records.first { it.type == "DIETARY_ENERGY" }
        assertEquals(550.0, energy.value)
        assertEquals("kcal", energy.unit)
        assertEquals("samsung-meal-1", energy.parentId)

        val poly = records.first { it.type == "DIETARY_POLYUNSATURATED_FAT" }
        assertEquals("g", poly.unit)
        assertEquals("samsung-meal-1", poly.parentId)
        assertTrue(records.none { it.type == "DIETARY_CAFFEINE" })
    }

    @Test
    fun nutrientWithoutAMealIsLoose() {
        val records = NutritionPayload.records(
            id = "hc-caffeine",
            startDate = "2026-09-18T09:00:00Z",
            endDate = "2026-09-18T09:00:00Z",
            zoneOffset = "+02:00",
            source = source,
            title = null,
            mealType = null,
            nutrients = listOf(
                NutritionPayload.Nutrient("caffeine", "DIETARY_CAFFEINE", 95.0, "mg"),
            ),
        )

        assertEquals(1, records.size)
        assertEquals("DIETARY_CAFFEINE", records[0].type)
        assertNull(records[0].parentId)
        assertEquals("mg", records[0].unit)
    }

    @Test
    fun undefinedSamsungMealIsNotAMealType() {
        assertNull(NutritionPayload.mealTypeFromSamsung("UNDEFINED"))
        assertEquals("morning_snack", NutritionPayload.mealTypeFromSamsung("MORNING_SNACK"))
    }

    @Test
    fun emptyMealIsDropped() {
        val records = NutritionPayload.records(
            id = "empty",
            startDate = "2026-09-18T12:00:00Z",
            endDate = "2026-09-18T12:00:00Z",
            zoneOffset = null,
            source = source,
            title = "  ",
            mealType = null,
            nutrients = emptyList(),
        )
        assertTrue(records.isEmpty())
    }
}
