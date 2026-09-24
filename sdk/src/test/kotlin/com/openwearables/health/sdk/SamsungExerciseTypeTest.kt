package com.openwearables.health.sdk

import kotlin.test.Test
import kotlin.test.assertEquals

class SamsungExerciseTypeTest {

    @Test
    fun cyclingCodeBecomesCycling() {
        assertEquals("CYCLING", SamsungExerciseType.toPayloadType(11007))
        assertEquals("CYCLING", SamsungExerciseType.toPayloadType("11007"))
    }

    @Test
    fun runningAndWalkingCodesMatchHealthConnectNames() {
        assertEquals("RUNNING", SamsungExerciseType.toPayloadType(1002))
        assertEquals("WALKING", SamsungExerciseType.toPayloadType(1001))
        assertEquals("CYCLING_STATIONARY", SamsungExerciseType.toPayloadType(15003))
    }

    @Test
    fun samsungEnumNamesUseTheSameVocabulary() {
        assertEquals("CYCLING", SamsungExerciseType.toPayloadType("BIKING"))
        assertEquals("CYCLING", SamsungExerciseType.toPayloadType("CYCLING"))
    }

    @Test
    fun unknownCodeDoesNotPassThroughAsANumber() {
        assertEquals("OTHER", SamsungExerciseType.toPayloadType(99999))
        assertEquals("OTHER", SamsungExerciseType.toPayloadType(null))
        assertEquals("OTHER", SamsungExerciseType.toPayloadType("UNKNOWN"))
    }
}
