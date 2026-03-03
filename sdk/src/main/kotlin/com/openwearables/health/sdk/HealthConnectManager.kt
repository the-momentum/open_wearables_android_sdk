package com.openwearables.health.sdk

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneOffset
import java.util.*
import kotlin.reflect.KClass

class HealthConnectManager(
    private val context: Context,
    private var activity: Activity?,
    private val logger: (String) -> Unit
) : HealthDataProvider {

    override val providerId = "google"
    override val providerName = "Health Connect"

    private var client: HealthConnectClient? = null
    private var trackedTypeIds: Set<String> = emptySet()
    private var permissionLauncher: ActivityResultLauncher<Set<String>>? = null
    private var pendingPermissionResult: CompletableDeferred<Set<String>>? = null
    private var launcherRegistered = false

    // -----------------------------------------------------------------------
    // HealthDataProvider interface
    // -----------------------------------------------------------------------

    override fun setActivity(activity: Activity?) {
        this.activity = activity
        registerPermissionLauncher()
    }

    private fun registerPermissionLauncher() {
        val act = activity as? ComponentActivity
        if (act == null || launcherRegistered) return

        try {
            val contract = PermissionController.createRequestPermissionResultContract()
            permissionLauncher = act.activityResultRegistry.register(
                "health_connect_permissions",
                contract
            ) { granted ->
                pendingPermissionResult?.complete(granted)
            }
            launcherRegistered = true
            logger("Health Connect permission launcher registered")
        } catch (e: Exception) {
            logger("Failed to register HC permission launcher: ${e.message}")
        }
    }

    fun unregisterPermissionLauncher() {
        permissionLauncher?.unregister()
        permissionLauncher = null
        launcherRegistered = false
    }

    override fun setTrackedTypes(typeIds: List<String>) {
        trackedTypeIds = typeIds.toSet()
        val validCount = typeIds.count { mapToRecordClass(it) != null }
        logger("Tracking $validCount Health Connect types (out of ${typeIds.size} requested)")
    }

    override fun getTrackedTypes(): Set<String> = trackedTypeIds

    override fun isAvailable(): Boolean {
        return try {
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun connect(): Boolean {
        if (!isAvailable()) {
            logger("Health Connect not available on this device")
            return false
        }
        return try {
            client = HealthConnectClient.getOrCreate(context)
            logger("Connected to Health Connect")
            true
        } catch (e: Exception) {
            logger("Failed to connect to Health Connect: ${e.message}")
            false
        }
    }

    override fun disconnect() {
        client = null
    }

    override suspend fun requestAuthorization(typeIds: List<String>): Boolean {
        trackedTypeIds = typeIds.filter { mapToRecordClass(it) != null }.toSet()

        val permissions = typeIds.mapNotNull { typeId ->
            mapToRecordClass(typeId)?.let { HealthPermission.getReadPermission(it) }
        }.toMutableSet()

        if (permissions.isEmpty()) {
            logger("No valid Health Connect types to authorize")
            return false
        }

        permissions.add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)

        if (client == null && !connect()) return false
        val hcClient = client ?: return false

        val alreadyGranted = hcClient.permissionController.getGrantedPermissions()
        val needed = permissions - alreadyGranted
        if (needed.isEmpty()) {
            logger("All ${permissions.size} Health Connect permissions already granted (including background read)")
            return true
        }

        val launcher = permissionLauncher
        if (launcher == null) {
            logger("Permission launcher not registered — cannot request HC permissions")
            return false
        }

        return try {
            val deferred = CompletableDeferred<Set<String>>()
            pendingPermissionResult = deferred

            logger("Launching Health Connect permission dialog for ${needed.size} permissions (includes background read)")
            launcher.launch(needed)

            val granted = deferred.await()
            pendingPermissionResult = null

            val totalGranted = alreadyGranted + granted
            val bgGranted = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in totalGranted
            val dataPermsGranted = (permissions - HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND).all { it in totalGranted }
            logger("Data permissions: ${if (dataPermsGranted) "all granted" else "some missing"}, background read: ${if (bgGranted) "granted" else "NOT granted"}")
            dataPermsGranted
        } catch (e: Exception) {
            logger("Health Connect permission request failed: ${e.message}")
            pendingPermissionResult = null
            false
        }
    }

    override suspend fun readData(
        typeId: String,
        sinceTimestamp: Long?,
        limit: Int
    ): ProviderReadResult = withContext(Dispatchers.IO) {
        if (client == null) withContext(Dispatchers.Main) { connect() }
        val hcClient = client ?: return@withContext ProviderReadResult(UnifiedHealthData(), null)

        try {
            when (typeId) {
                "steps" -> readRecordType<StepsRecord>(hcClient, typeId, sinceTimestamp, limit) { convertSteps(it) }
                "heartRate" -> readRecordType<HeartRateRecord>(hcClient, typeId, sinceTimestamp, limit) { convertHeartRate(it) }
                "restingHeartRate" -> readRecordType<RestingHeartRateRecord>(hcClient, typeId, sinceTimestamp, limit) { convertRestingHeartRate(it) }
                "heartRateVariabilitySDNN" -> readRecordType<HeartRateVariabilityRmssdRecord>(hcClient, typeId, sinceTimestamp, limit) { convertHrv(it) }
                "oxygenSaturation" -> readRecordType<OxygenSaturationRecord>(hcClient, typeId, sinceTimestamp, limit) { convertOxygenSaturation(it) }
                "bloodPressure", "bloodPressureSystolic", "bloodPressureDiastolic" -> readRecordType<BloodPressureRecord>(hcClient, typeId, sinceTimestamp, limit) { convertBloodPressure(it) }
                "bloodGlucose" -> readRecordType<BloodGlucoseRecord>(hcClient, typeId, sinceTimestamp, limit) { convertBloodGlucose(it) }
                "activeEnergy" -> readRecordType<ActiveCaloriesBurnedRecord>(hcClient, typeId, sinceTimestamp, limit) { convertActiveCalories(it) }
                "basalEnergy" -> readRecordType<BasalMetabolicRateRecord>(hcClient, typeId, sinceTimestamp, limit) { convertBasalCalories(it) }
                "bodyTemperature" -> readRecordType<BodyTemperatureRecord>(hcClient, typeId, sinceTimestamp, limit) { convertBodyTemperature(it) }
                "bodyMass" -> readRecordType<WeightRecord>(hcClient, typeId, sinceTimestamp, limit) { convertWeight(it) }
                "height" -> readRecordType<HeightRecord>(hcClient, typeId, sinceTimestamp, limit) { convertHeight(it) }
                "bodyFatPercentage" -> readRecordType<BodyFatRecord>(hcClient, typeId, sinceTimestamp, limit) { convertBodyFat(it) }
                "leanBodyMass" -> readRecordType<LeanBodyMassRecord>(hcClient, typeId, sinceTimestamp, limit) { convertLeanBodyMass(it) }
                "flightsClimbed" -> readRecordType<FloorsClimbedRecord>(hcClient, typeId, sinceTimestamp, limit) { convertFloors(it) }
                "distanceWalkingRunning" -> readRecordType<DistanceRecord>(hcClient, typeId, sinceTimestamp, limit) { convertDistance(it) }
                "water", "dietaryWater" -> readRecordType<HydrationRecord>(hcClient, typeId, sinceTimestamp, limit) { convertHydration(it) }
                "vo2Max" -> readRecordType<Vo2MaxRecord>(hcClient, typeId, sinceTimestamp, limit) { convertVo2Max(it) }
                "respiratoryRate" -> readRecordType<RespiratoryRateRecord>(hcClient, typeId, sinceTimestamp, limit) { convertRespiratoryRate(it) }
                "distanceCycling" -> readRecordType<DistanceRecord>(hcClient, typeId, sinceTimestamp, limit) { convertDistance(it) }
                "workout" -> readWorkouts(hcClient, sinceTimestamp, limit)
                "sleep" -> readSleep(hcClient, sinceTimestamp, limit)
                else -> ProviderReadResult(UnifiedHealthData(), null)
            }
        } catch (e: SecurityException) {
            logger("  $typeId: missing permission, skipping")
            ProviderReadResult(UnifiedHealthData(), null)
        } catch (e: Exception) {
            logger("Failed to read $typeId from Health Connect: ${e.javaClass.simpleName}: ${e.message}")
            ProviderReadResult(UnifiedHealthData(), null)
        }
    }

    // -----------------------------------------------------------------------
    // Generic reader
    // -----------------------------------------------------------------------

    private suspend inline fun <reified T : Record> readRecordType(
        client: HealthConnectClient,
        typeId: String,
        sinceTimestamp: Long?,
        limit: Int,
        crossinline convert: (List<T>) -> ProviderReadResult
    ): ProviderReadResult {
        val timeFilter = if (sinceTimestamp != null) {
            TimeRangeFilter.after(Instant.ofEpochMilli(sinceTimestamp + 1))
        } else {
            TimeRangeFilter.before(Instant.now())
        }

        val request = ReadRecordsRequest(
            recordType = T::class,
            timeRangeFilter = timeFilter,
            ascendingOrder = true,
            pageSize = limit
        )

        val response = client.readRecords(request)
        if (response.records.isEmpty()) return ProviderReadResult(UnifiedHealthData(), null)

        logger("Read ${response.records.size} ${T::class.simpleName} records")
        return convert(response.records)
    }

    // -----------------------------------------------------------------------
    // Record converters
    // -----------------------------------------------------------------------

    private fun buildSource(metadata: Metadata): UnifiedSource {
        val device = metadata.device
        return UnifiedSource(
            appId = metadata.dataOrigin.packageName,
            deviceId = null,
            deviceName = null,
            deviceManufacturer = device?.manufacturer,
            deviceModel = device?.model,
            deviceType = device?.type?.let { DeviceTypeMapper.fromHealthConnectDeviceType(it) },
            recordingMethod = metadata.recordingMethod.let { DeviceTypeMapper.fromHealthConnectRecordingMethod(it) }
        )
    }

    private fun zoneStr(offset: ZoneOffset?): String? = offset?.toString()

    private fun instantToIso(instant: Instant): String = UnifiedTimestamp.fromEpochMs(instant.toEpochMilli())

    // ---- Steps ----
    private fun convertSteps(records: List<StepsRecord>): ProviderReadResult {
        var maxTs: Long? = null
        val unified = records.map { r ->
            val end = r.endTime.toEpochMilli(); if (maxTs == null || end > maxTs!!) maxTs = end
            UnifiedRecord(r.metadata.id, "STEP_COUNT", instantToIso(r.startTime), instantToIso(r.endTime),
                zoneStr(r.startZoneOffset), buildSource(r.metadata), r.count.toDouble(), "count", null, null)
        }
        return ProviderReadResult(UnifiedHealthData(records = unified), maxTs)
    }

    // ---- Heart Rate (split samples) ----
    private fun convertHeartRate(records: List<HeartRateRecord>): ProviderReadResult {
        var maxTs: Long? = null
        val unified = mutableListOf<UnifiedRecord>()
        for (r in records) {
            val parentId = r.metadata.id
            val source = buildSource(r.metadata)
            val zo = zoneStr(r.startZoneOffset)
            for ((idx, sample) in r.samples.withIndex()) {
                val ts = sample.time.toEpochMilli(); if (maxTs == null || ts > maxTs!!) maxTs = ts
                val iso = instantToIso(sample.time)
                unified.add(UnifiedRecord("$parentId-s$idx", "HEART_RATE", iso, iso, zo, source,
                    sample.beatsPerMinute.toDouble(), "bpm", parentId, null))
            }
        }
        return ProviderReadResult(UnifiedHealthData(records = unified), maxTs)
    }

    // ---- Resting Heart Rate ----
    private fun convertRestingHeartRate(records: List<RestingHeartRateRecord>): ProviderReadResult {
        var maxTs: Long? = null
        val unified = records.map { r ->
            val ts = r.time.toEpochMilli(); if (maxTs == null || ts > maxTs!!) maxTs = ts
            val iso = instantToIso(r.time)
            UnifiedRecord(r.metadata.id, "RESTING_HEART_RATE", iso, iso, zoneStr(r.zoneOffset), buildSource(r.metadata),
                r.beatsPerMinute.toDouble(), "bpm", null, null)
        }
        return ProviderReadResult(UnifiedHealthData(records = unified), maxTs)
    }

    // ---- HRV ----
    private fun convertHrv(records: List<HeartRateVariabilityRmssdRecord>): ProviderReadResult {
        var maxTs: Long? = null
        val unified = records.map { r ->
            val ts = r.time.toEpochMilli(); if (maxTs == null || ts > maxTs!!) maxTs = ts
            val iso = instantToIso(r.time)
            UnifiedRecord(r.metadata.id, "HEART_RATE_VARIABILITY", iso, iso, zoneStr(r.zoneOffset),
                buildSource(r.metadata), r.heartRateVariabilityMillis, "ms", null, mapOf("method" to "rmssd"))
        }
        return ProviderReadResult(UnifiedHealthData(records = unified), maxTs)
    }

    // ---- Oxygen Saturation ----
    private fun convertOxygenSaturation(records: List<OxygenSaturationRecord>): ProviderReadResult {
        var maxTs: Long? = null
        val unified = records.map { r ->
            val ts = r.time.toEpochMilli(); if (maxTs == null || ts > maxTs!!) maxTs = ts
            val iso = instantToIso(r.time)
            UnifiedRecord(r.metadata.id, "OXYGEN_SATURATION", iso, iso, zoneStr(r.zoneOffset),
                buildSource(r.metadata), r.percentage.value, "%", null, null)
        }
        return ProviderReadResult(UnifiedHealthData(records = unified), maxTs)
    }

    // ---- Blood Pressure (split) ----
    private fun convertBloodPressure(records: List<BloodPressureRecord>): ProviderReadResult {
        var maxTs: Long? = null
        val unified = mutableListOf<UnifiedRecord>()
        for (r in records) {
            val ts = r.time.toEpochMilli(); if (maxTs == null || ts > maxTs!!) maxTs = ts
            val iso = instantToIso(r.time)
            val src = buildSource(r.metadata)
            val zo = zoneStr(r.zoneOffset)
            val parentId = r.metadata.id
            val meta = mutableMapOf<String, Any?>()
            if (r.bodyPosition != BloodPressureRecord.BODY_POSITION_UNKNOWN)
                meta["bodyPosition"] = mapBodyPosition(r.bodyPosition)
            if (r.measurementLocation != BloodPressureRecord.MEASUREMENT_LOCATION_UNKNOWN)
                meta["measurementLocation"] = mapMeasurementLocation(r.measurementLocation)

            unified.add(UnifiedRecord("$parentId-sys", "BLOOD_PRESSURE_SYSTOLIC", iso, iso, zo, src,
                r.systolic.inMillimetersOfMercury, "mmHg", parentId, meta.ifEmpty { null }))
            unified.add(UnifiedRecord("$parentId-dia", "BLOOD_PRESSURE_DIASTOLIC", iso, iso, zo, src,
                r.diastolic.inMillimetersOfMercury, "mmHg", parentId, meta.ifEmpty { null }))
        }
        return ProviderReadResult(UnifiedHealthData(records = unified), maxTs)
    }

    private fun mapBodyPosition(pos: Int): String = when (pos) {
        BloodPressureRecord.BODY_POSITION_STANDING_UP -> "standing"
        BloodPressureRecord.BODY_POSITION_SITTING_DOWN -> "sitting"
        BloodPressureRecord.BODY_POSITION_LYING_DOWN -> "lying_down"
        BloodPressureRecord.BODY_POSITION_RECLINING -> "reclining"
        else -> "unknown"
    }

    private fun mapMeasurementLocation(loc: Int): String = when (loc) {
        BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_WRIST -> "left_wrist"
        BloodPressureRecord.MEASUREMENT_LOCATION_RIGHT_WRIST -> "right_wrist"
        BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_UPPER_ARM -> "left_upper_arm"
        BloodPressureRecord.MEASUREMENT_LOCATION_RIGHT_UPPER_ARM -> "right_upper_arm"
        else -> "unknown"
    }

    // ---- Blood Glucose ----
    private fun convertBloodGlucose(records: List<BloodGlucoseRecord>): ProviderReadResult {
        var maxTs: Long? = null
        val unified = records.map { r ->
            val ts = r.time.toEpochMilli(); if (maxTs == null || ts > maxTs!!) maxTs = ts
            val iso = instantToIso(r.time)
            val meta = mutableMapOf<String, Any?>()
            if (r.specimenSource != BloodGlucoseRecord.SPECIMEN_SOURCE_UNKNOWN)
                meta["specimenSource"] = mapSpecimenSource(r.specimenSource)
            if (r.relationToMeal != BloodGlucoseRecord.RELATION_TO_MEAL_UNKNOWN)
                meta["relationToMeal"] = mapRelationToMeal(r.relationToMeal)
            UnifiedRecord(r.metadata.id, "BLOOD_GLUCOSE", iso, iso, zoneStr(r.zoneOffset),
                buildSource(r.metadata), r.level.inMillimolesPerLiter, "mmol/L", null, meta.ifEmpty { null })
        }
        return ProviderReadResult(UnifiedHealthData(records = unified), maxTs)
    }

    private fun mapSpecimenSource(src: Int): String = when (src) {
        BloodGlucoseRecord.SPECIMEN_SOURCE_INTERSTITIAL_FLUID -> "interstitial_fluid"
        BloodGlucoseRecord.SPECIMEN_SOURCE_CAPILLARY_BLOOD -> "capillary_blood"
        BloodGlucoseRecord.SPECIMEN_SOURCE_WHOLE_BLOOD -> "whole_blood"
        BloodGlucoseRecord.SPECIMEN_SOURCE_PLASMA -> "plasma"
        BloodGlucoseRecord.SPECIMEN_SOURCE_SERUM -> "serum"
        BloodGlucoseRecord.SPECIMEN_SOURCE_TEARS -> "tears"
        else -> "unknown"
    }

    private fun mapRelationToMeal(rel: Int): String = when (rel) {
        BloodGlucoseRecord.RELATION_TO_MEAL_FASTING -> "fasting"
        BloodGlucoseRecord.RELATION_TO_MEAL_BEFORE_MEAL -> "before_meal"
        BloodGlucoseRecord.RELATION_TO_MEAL_AFTER_MEAL -> "after_meal"
        BloodGlucoseRecord.RELATION_TO_MEAL_GENERAL -> "general"
        else -> "unknown"
    }

    // ---- Active Calories ----
    private fun convertActiveCalories(records: List<ActiveCaloriesBurnedRecord>): ProviderReadResult {
        var maxTs: Long? = null
        val unified = records.map { r ->
            val end = r.endTime.toEpochMilli(); if (maxTs == null || end > maxTs!!) maxTs = end
            UnifiedRecord(r.metadata.id, "ACTIVE_CALORIES_BURNED", instantToIso(r.startTime), instantToIso(r.endTime),
                zoneStr(r.startZoneOffset), buildSource(r.metadata), r.energy.inKilocalories, "kcal", null, null)
        }
        return ProviderReadResult(UnifiedHealthData(records = unified), maxTs)
    }

    // ---- Basal Metabolic Rate ----
    private fun convertBasalCalories(records: List<BasalMetabolicRateRecord>): ProviderReadResult {
        var maxTs: Long? = null
        val unified = records.map { r ->
            val ts = r.time.toEpochMilli(); if (maxTs == null || ts > maxTs!!) maxTs = ts
            val iso = instantToIso(r.time)
            UnifiedRecord(r.metadata.id, "BASAL_METABOLIC_RATE", iso, iso,
                zoneStr(r.zoneOffset), buildSource(r.metadata), r.basalMetabolicRate.inKilocaloriesPerDay, "kcal/day", null, null)
        }
        return ProviderReadResult(UnifiedHealthData(records = unified), maxTs)
    }

    // ---- Body Temperature ----
    private fun convertBodyTemperature(records: List<BodyTemperatureRecord>): ProviderReadResult {
        var maxTs: Long? = null
        val unified = records.map { r ->
            val ts = r.time.toEpochMilli(); if (maxTs == null || ts > maxTs!!) maxTs = ts
            val iso = instantToIso(r.time)
            val meta = if (r.measurementLocation != 0)
                mapOf("measurementLocation" to mapTempLocation(r.measurementLocation)) else null
            UnifiedRecord(r.metadata.id, "BODY_TEMPERATURE", iso, iso, zoneStr(r.zoneOffset),
                buildSource(r.metadata), r.temperature.inCelsius, "°C", null, meta)
        }
        return ProviderReadResult(UnifiedHealthData(records = unified), maxTs)
    }

    private fun mapTempLocation(loc: Int): String = when (loc) {
        1 -> "armpit"
        2 -> "finger"
        3 -> "forehead"
        4 -> "mouth"
        5 -> "rectum"
        6 -> "temporal_artery"
        7 -> "toe"
        8 -> "ear"
        9 -> "wrist"
        10 -> "vagina"
        else -> "unknown"
    }

    // ---- Weight ----
    private fun convertWeight(records: List<WeightRecord>): ProviderReadResult {
        var maxTs: Long? = null
        val unified = records.map { r ->
            val ts = r.time.toEpochMilli(); if (maxTs == null || ts > maxTs!!) maxTs = ts
            val iso = instantToIso(r.time)
            UnifiedRecord(r.metadata.id, "WEIGHT", iso, iso, zoneStr(r.zoneOffset),
                buildSource(r.metadata), r.weight.inKilograms, "kg", null, null)
        }
        return ProviderReadResult(UnifiedHealthData(records = unified), maxTs)
    }

    // ---- Height ----
    private fun convertHeight(records: List<HeightRecord>): ProviderReadResult {
        var maxTs: Long? = null
        val unified = records.map { r ->
            val ts = r.time.toEpochMilli(); if (maxTs == null || ts > maxTs!!) maxTs = ts
            val iso = instantToIso(r.time)
            UnifiedRecord(r.metadata.id, "HEIGHT", iso, iso, zoneStr(r.zoneOffset),
                buildSource(r.metadata), r.height.inMeters, "m", null, null)
        }
        return ProviderReadResult(UnifiedHealthData(records = unified), maxTs)
    }

    // ---- Body Fat ----
    private fun convertBodyFat(records: List<BodyFatRecord>): ProviderReadResult {
        var maxTs: Long? = null
        val unified = records.map { r ->
            val ts = r.time.toEpochMilli(); if (maxTs == null || ts > maxTs!!) maxTs = ts
            val iso = instantToIso(r.time)
            UnifiedRecord(r.metadata.id, "BODY_FAT", iso, iso, zoneStr(r.zoneOffset),
                buildSource(r.metadata), r.percentage.value, "%", null, null)
        }
        return ProviderReadResult(UnifiedHealthData(records = unified), maxTs)
    }

    // ---- Lean Body Mass ----
    private fun convertLeanBodyMass(records: List<LeanBodyMassRecord>): ProviderReadResult {
        var maxTs: Long? = null
        val unified = records.map { r ->
            val ts = r.time.toEpochMilli(); if (maxTs == null || ts > maxTs!!) maxTs = ts
            val iso = instantToIso(r.time)
            UnifiedRecord(r.metadata.id, "LEAN_BODY_MASS", iso, iso, zoneStr(r.zoneOffset),
                buildSource(r.metadata), r.mass.inKilograms, "kg", null, null)
        }
        return ProviderReadResult(UnifiedHealthData(records = unified), maxTs)
    }

    // ---- Floors Climbed ----
    private fun convertFloors(records: List<FloorsClimbedRecord>): ProviderReadResult {
        var maxTs: Long? = null
        val unified = records.map { r ->
            val end = r.endTime.toEpochMilli(); if (maxTs == null || end > maxTs!!) maxTs = end
            UnifiedRecord(r.metadata.id, "FLOORS_CLIMBED", instantToIso(r.startTime), instantToIso(r.endTime),
                zoneStr(r.startZoneOffset), buildSource(r.metadata), r.floors, "count", null, null)
        }
        return ProviderReadResult(UnifiedHealthData(records = unified), maxTs)
    }

    // ---- Distance ----
    private fun convertDistance(records: List<DistanceRecord>): ProviderReadResult {
        var maxTs: Long? = null
        val unified = records.map { r ->
            val end = r.endTime.toEpochMilli(); if (maxTs == null || end > maxTs!!) maxTs = end
            UnifiedRecord(r.metadata.id, "DISTANCE", instantToIso(r.startTime), instantToIso(r.endTime),
                zoneStr(r.startZoneOffset), buildSource(r.metadata), r.distance.inMeters, "m", null, null)
        }
        return ProviderReadResult(UnifiedHealthData(records = unified), maxTs)
    }

    // ---- Hydration (L → mL) ----
    private fun convertHydration(records: List<HydrationRecord>): ProviderReadResult {
        var maxTs: Long? = null
        val unified = records.map { r ->
            val end = r.endTime.toEpochMilli(); if (maxTs == null || end > maxTs!!) maxTs = end
            UnifiedRecord(r.metadata.id, "HYDRATION", instantToIso(r.startTime), instantToIso(r.endTime),
                zoneStr(r.startZoneOffset), buildSource(r.metadata), r.volume.inLiters * 1000.0, "mL", null, null)
        }
        return ProviderReadResult(UnifiedHealthData(records = unified), maxTs)
    }

    // ---- Respiratory Rate ----
    private fun convertRespiratoryRate(records: List<RespiratoryRateRecord>): ProviderReadResult {
        var maxTs: Long? = null
        val unified = records.map { r ->
            val ts = r.time.toEpochMilli(); if (maxTs == null || ts > maxTs!!) maxTs = ts
            val iso = instantToIso(r.time)
            UnifiedRecord(r.metadata.id, "RESPIRATORY_RATE", iso, iso, zoneStr(r.zoneOffset),
                buildSource(r.metadata), r.rate, "breaths/min", null, null)
        }
        return ProviderReadResult(UnifiedHealthData(records = unified), maxTs)
    }

    // ---- VO2 Max ----
    private fun convertVo2Max(records: List<Vo2MaxRecord>): ProviderReadResult {
        var maxTs: Long? = null
        val unified = records.map { r ->
            val ts = r.time.toEpochMilli(); if (maxTs == null || ts > maxTs!!) maxTs = ts
            val iso = instantToIso(r.time)
            UnifiedRecord(r.metadata.id, "VO2_MAX", iso, iso, zoneStr(r.zoneOffset),
                buildSource(r.metadata), r.vo2MillilitersPerMinuteKilogram, "mL/kg/min", null, null)
        }
        return ProviderReadResult(UnifiedHealthData(records = unified), maxTs)
    }

    // -----------------------------------------------------------------------
    // Workouts
    // -----------------------------------------------------------------------

    private suspend fun readWorkouts(client: HealthConnectClient, sinceTimestamp: Long?, limit: Int): ProviderReadResult {
        val timeFilter = if (sinceTimestamp != null) {
            TimeRangeFilter.after(Instant.ofEpochMilli(sinceTimestamp + 1))
        } else {
            TimeRangeFilter.before(Instant.now())
        }

        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = timeFilter,
                ascendingOrder = true,
                pageSize = limit
            )
        )
        if (response.records.isEmpty()) return ProviderReadResult(UnifiedHealthData(), null)

        var maxTs: Long? = null
        val workouts = response.records.map { r ->
            val end = r.endTime.toEpochMilli(); if (maxTs == null || end > maxTs!!) maxTs = end
            val source = buildSource(r.metadata)
            val zo = zoneStr(r.startZoneOffset)
            val duration = java.time.Duration.between(r.startTime, r.endTime).seconds.toDouble()

            val values = mutableListOf<Map<String, Any>>(
                mapOf("type" to "duration", "value" to duration, "unit" to "s")
            )

            val segments = r.segments.map { seg ->
                mapOf<String, Any?>(
                    "startDate" to instantToIso(seg.startTime),
                    "endDate" to instantToIso(seg.endTime),
                    "type" to mapSegmentType(seg.segmentType),
                    "repetitions" to seg.repetitions
                )
            }

            val laps = r.laps.map { lap ->
                mapOf<String, Any?>(
                    "startDate" to instantToIso(lap.startTime),
                    "endDate" to instantToIso(lap.endTime),
                    "distanceM" to lap.length?.inMeters
                )
            }

            val route: List<Map<String, Any?>>? = try {
                val routeResult = r.exerciseRouteResult
                if (routeResult is ExerciseRouteResult.Data) {
                    routeResult.exerciseRoute.route.map { loc ->
                        mapOf<String, Any?>(
                            "timestamp" to instantToIso(loc.time),
                            "latitude" to loc.latitude,
                            "longitude" to loc.longitude,
                            "altitudeM" to loc.altitude?.inMeters,
                            "horizontalAccuracyM" to loc.horizontalAccuracy?.inMeters,
                            "verticalAccuracyM" to loc.verticalAccuracy?.inMeters
                        )
                    }
                } else null
            } catch (_: Exception) { null }

            UnifiedWorkout(
                id = r.metadata.id,
                parentId = null,
                type = mapExerciseType(r.exerciseType),
                startDate = instantToIso(r.startTime),
                endDate = instantToIso(r.endTime),
                zoneOffset = zo,
                source = source,
                title = r.title,
                notes = r.notes,
                values = values.ifEmpty { null },
                segments = segments.ifEmpty { null },
                laps = laps.ifEmpty { null },
                route = route?.ifEmpty { null },
                samples = null,
                metadata = if (r.exerciseRouteResult is ExerciseRouteResult.Data) mapOf("hasRoute" to true) else null
            )
        }

        return ProviderReadResult(UnifiedHealthData(workouts = workouts), maxTs)
    }

    private fun mapSegmentType(type: Int): String = when (type) {
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING -> "running"
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_WALKING -> "walking"
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING -> "stretching"
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_OPEN_WATER,
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_POOL -> "swimming"
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING -> "cycling"
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS -> "bench_press"
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT -> "squat"
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_DEADLIFT -> "deadlift"
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_PLANK -> "plank"
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_REST -> "rest"
        else -> "other_$type"
    }

    private fun mapExerciseType(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON -> "BADMINTON"
        ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL -> "BASEBALL"
        ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL -> "BASKETBALL"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "CYCLING"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "CYCLING_STATIONARY"
        ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP -> "BOOT_CAMP"
        ExerciseSessionRecord.EXERCISE_TYPE_BOXING -> "BOXING"
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "CALISTHENICS"
        ExerciseSessionRecord.EXERCISE_TYPE_CRICKET -> "CRICKET"
        ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "DANCING"
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "ELLIPTICAL"
        ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS -> "EXERCISE_CLASS"
        ExerciseSessionRecord.EXERCISE_TYPE_FENCING -> "FENCING"
        ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN -> "FOOTBALL_AMERICAN"
        ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AUSTRALIAN -> "FOOTBALL_AUSTRALIAN"
        ExerciseSessionRecord.EXERCISE_TYPE_FRISBEE_DISC -> "FRISBEE_DISC"
        ExerciseSessionRecord.EXERCISE_TYPE_GOLF -> "GOLF"
        ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING -> "GUIDED_BREATHING"
        ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS -> "GYMNASTICS"
        ExerciseSessionRecord.EXERCISE_TYPE_HANDBALL -> "HANDBALL"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "HIIT"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "HIKING"
        ExerciseSessionRecord.EXERCISE_TYPE_ICE_HOCKEY -> "ICE_HOCKEY"
        ExerciseSessionRecord.EXERCISE_TYPE_ICE_SKATING -> "ICE_SKATING"
        ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS -> "MARTIAL_ARTS"
        ExerciseSessionRecord.EXERCISE_TYPE_PADDLING -> "PADDLING"
        ExerciseSessionRecord.EXERCISE_TYPE_PARAGLIDING -> "PARAGLIDING"
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "PILATES"
        ExerciseSessionRecord.EXERCISE_TYPE_RACQUETBALL -> "RACQUETBALL"
        ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING -> "ROCK_CLIMBING"
        ExerciseSessionRecord.EXERCISE_TYPE_ROLLER_HOCKEY -> "ROLLER_HOCKEY"
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING -> "ROWING"
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "ROWING_MACHINE"
        ExerciseSessionRecord.EXERCISE_TYPE_RUGBY -> "RUGBY"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "RUNNING"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "RUNNING_TREADMILL"
        ExerciseSessionRecord.EXERCISE_TYPE_SAILING -> "SAILING"
        ExerciseSessionRecord.EXERCISE_TYPE_SCUBA_DIVING -> "SCUBA_DIVING"
        ExerciseSessionRecord.EXERCISE_TYPE_SKATING -> "SKATING"
        ExerciseSessionRecord.EXERCISE_TYPE_SKIING -> "SKIING"
        ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING -> "SNOWBOARDING"
        ExerciseSessionRecord.EXERCISE_TYPE_SNOWSHOEING -> "SNOWSHOEING"
        ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "SOCCER"
        ExerciseSessionRecord.EXERCISE_TYPE_SOFTBALL -> "SOFTBALL"
        ExerciseSessionRecord.EXERCISE_TYPE_SQUASH -> "SQUASH"
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING -> "STAIR_CLIMBING"
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE -> "STAIR_CLIMBING_MACHINE"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "STRENGTH_TRAINING"
        ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> "STRETCHING"
        ExerciseSessionRecord.EXERCISE_TYPE_SURFING -> "SURFING"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "SWIMMING_OPEN_WATER"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "SWIMMING_POOL"
        ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS -> "TABLE_TENNIS"
        ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> "TENNIS"
        ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL -> "VOLLEYBALL"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "WALKING"
        ExerciseSessionRecord.EXERCISE_TYPE_WATER_POLO -> "WATER_POLO"
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "WEIGHTLIFTING"
        ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR -> "WHEELCHAIR"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "YOGA"
        ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT -> "OTHER"
        else -> "UNKNOWN_$type"
    }

    // -----------------------------------------------------------------------
    // Sleep
    // -----------------------------------------------------------------------

    private suspend fun readSleep(client: HealthConnectClient, sinceTimestamp: Long?, limit: Int): ProviderReadResult {
        val timeFilter = if (sinceTimestamp != null) {
            TimeRangeFilter.after(Instant.ofEpochMilli(sinceTimestamp + 1))
        } else {
            TimeRangeFilter.before(Instant.now())
        }

        logger("Reading sleep sessions (since: $sinceTimestamp)")
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = timeFilter,
                ascendingOrder = true,
                pageSize = limit
            )
        )
        logger("Sleep query returned ${response.records.size} sessions")
        if (response.records.isEmpty()) return ProviderReadResult(UnifiedHealthData(), null)

        var maxTs: Long? = null
        val sleepEntries = mutableListOf<UnifiedSleep>()

        for (r in response.records) {
            try {
                val end = r.endTime.toEpochMilli(); if (maxTs == null || end > maxTs!!) maxTs = end
                val source = buildSource(r.metadata)
                val zo = zoneStr(r.startZoneOffset)
                val parentId = r.metadata.id

                logger("  Sleep session $parentId: ${instantToIso(r.startTime)} → ${instantToIso(r.endTime)}")

                val stages = try { r.stages } catch (e: Exception) {
                    logger("  Failed to read stages for $parentId: ${e.message}")
                    emptyList()
                }

                if (stages.isEmpty()) {
                    logger("  No stages, adding as single 'sleeping' entry")
                    sleepEntries.add(UnifiedSleep(
                        parentId, null, "sleeping",
                        instantToIso(r.startTime), instantToIso(r.endTime),
                        zo, source, null, null
                    ))
                } else {
                    logger("  ${stages.size} stages found")
                    for ((idx, stage) in stages.withIndex()) {
                        sleepEntries.add(UnifiedSleep(
                            id = "$parentId-s$idx",
                            parentId = parentId,
                            stage = mapSleepStage(stage.stage),
                            startDate = instantToIso(stage.startTime),
                            endDate = instantToIso(stage.endTime),
                            zoneOffset = zo,
                            source = source,
                            values = null,
                            metadata = null
                        ))
                    }
                }
            } catch (e: Exception) {
                logger("  Failed to process sleep session: ${e.message}")
            }
        }

        logger("Total sleep entries: ${sleepEntries.size}")
        return ProviderReadResult(UnifiedHealthData(sleep = sleepEntries), maxTs)
    }

    private fun mapSleepStage(stage: Int): String = when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE -> "awake"
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "in_bed"
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> "sleeping"
        SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
        SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
        SleepSessionRecord.STAGE_TYPE_REM -> "rem"
        else -> "unknown"
    }

    // -----------------------------------------------------------------------
    // Type mapping
    // -----------------------------------------------------------------------

    private fun mapToRecordClass(typeId: String): KClass<out Record>? = when (typeId) {
        "steps" -> StepsRecord::class
        "heartRate" -> HeartRateRecord::class
        "restingHeartRate" -> RestingHeartRateRecord::class
        "heartRateVariabilitySDNN" -> HeartRateVariabilityRmssdRecord::class
        "oxygenSaturation" -> OxygenSaturationRecord::class
        "bloodPressure", "bloodPressureSystolic", "bloodPressureDiastolic" -> BloodPressureRecord::class
        "bloodGlucose" -> BloodGlucoseRecord::class
        "activeEnergy" -> ActiveCaloriesBurnedRecord::class
        "basalEnergy" -> BasalMetabolicRateRecord::class
        "bodyTemperature" -> BodyTemperatureRecord::class
        "bodyMass" -> WeightRecord::class
        "height" -> HeightRecord::class
        "bodyFatPercentage" -> BodyFatRecord::class
        "leanBodyMass" -> LeanBodyMassRecord::class
        "flightsClimbed" -> FloorsClimbedRecord::class
        "distanceWalkingRunning" -> DistanceRecord::class
        "water", "dietaryWater" -> HydrationRecord::class
        "vo2Max" -> Vo2MaxRecord::class
        "respiratoryRate" -> RespiratoryRateRecord::class
        "distanceCycling" -> DistanceRecord::class
        "workout" -> ExerciseSessionRecord::class
        "sleep" -> SleepSessionRecord::class
        else -> null
    }
}
