package com.openwearables.health.sdk

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.DeviceManager
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.data.DataSource
import com.samsung.android.sdk.health.data.device.Device
import com.samsung.android.sdk.health.data.device.DeviceGroup
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import com.samsung.android.sdk.health.data.request.Ordering
import com.samsung.android.sdk.health.data.request.AggregateRequest
import com.samsung.android.sdk.health.data.request.ReadDataRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.*

class SamsungHealthManager(
    private val context: Context,
    private var activity: Activity?,
    private val logger: (String) -> Unit
) : HealthDataProvider {

    override val providerId = "samsung"
    override val providerName = "Samsung Health"

    private var healthDataStore: HealthDataStore? = null
    private var deviceManager: DeviceManager? = null
    private var trackedTypeIds: Set<String> = emptySet()
    private var deviceCache: MutableMap<String, Device> = mutableMapOf()

    companion object {
        private const val SAMSUNG_HEALTH_PACKAGE = "com.sec.android.app.shealth"
        private const val MIN_SAMSUNG_HEALTH_VERSION = 6030002
    }

    // -----------------------------------------------------------------------
    // HealthDataProvider interface
    // -----------------------------------------------------------------------

    override fun setActivity(activity: Activity?) {
        this.activity = activity
    }

    override fun setTrackedTypes(typeIds: List<String>) {
        trackedTypeIds = typeIds.filter { isSupportedType(it) }.toSet()
        logger("Tracking ${trackedTypeIds.size} Samsung Health types: ${trackedTypeIds.joinToString()}")
    }

    private fun isSupportedType(typeId: String): Boolean =
        mapToDataType(typeId) != null || getAggregateConfig(typeId) != null

    override fun getTrackedTypes(): Set<String> = trackedTypeIds

    override fun isAvailable(): Boolean {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(SAMSUNG_HEALTH_PACKAGE, 0)
            @Suppress("DEPRECATION")
            val versionCode = packageInfo.versionCode
            versionCode >= MIN_SAMSUNG_HEALTH_VERSION
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun connect(): Boolean = withContext(Dispatchers.Main) {
        if (!isAvailable()) {
            logger("Samsung Health not available on this device")
            return@withContext false
        }
        try {
            val store = HealthDataService.getStore(context)
            healthDataStore = store
            deviceManager = store.getDeviceManager()
            loadConnectedDevices()
            logger("Connected to Samsung Health")
            true
        } catch (e: Exception) {
            logger("Failed to connect to Samsung Health: ${e.message}")
            false
        }
    }

    override fun disconnect() {
        healthDataStore = null
        deviceManager = null
    }

    override suspend fun requestAuthorization(typeIds: List<String>): Boolean {
        trackedTypeIds = typeIds.filter { isSupportedType(it) }.toSet()
        val dataTypes = typeIds.mapNotNull { mapToDataType(it) }.toSet()
        if (dataTypes.isEmpty()) {
            logger("No valid Samsung Health types to authorize")
            return false
        }
        if (healthDataStore == null && !connect()) return false

        val store = healthDataStore ?: return false
        val act = activity ?: run {
            logger("Activity not available for permission request")
            return false
        }

        return withContext(Dispatchers.Main) {
            try {
                val permissions = dataTypes.map { Permission.of(it, AccessType.READ) }.toSet()
                logger("Requesting ${permissions.size} Samsung Health permissions...")
                val granted = store.requestPermissions(permissions, act)
                val allGranted = granted.size == permissions.size
                logger(if (allGranted) "All permissions granted" else "Granted ${granted.size}/${permissions.size}")
                allGranted
            } catch (e: Exception) {
                logger("Permission request failed: ${e.message}")
                false
            }
        }
    }

    /**
     * Reads raw Samsung data for [typeId] and converts it to unified format.
     */
    override suspend fun readData(
        typeId: String,
        sinceTimestamp: Long?,
        limit: Int
    ): ProviderReadResult {
        val rawRecords = readRawData(typeId, sinceTimestamp, limit)
        if (rawRecords.isEmpty()) return ProviderReadResult(UnifiedHealthData(), null)
        return convertToUnified(typeId, rawRecords)
    }

    // -----------------------------------------------------------------------
    // Raw data reading (Samsung SDK)
    // -----------------------------------------------------------------------

    private suspend fun readRawData(
        typeId: String,
        sinceTimestamp: Long?,
        limit: Int
    ): List<HealthDataRecord> = withContext(Dispatchers.IO) {
        val aggregateConfig = getAggregateConfig(typeId)
        if (aggregateConfig != null) {
            return@withContext readAggregateData(typeId, aggregateConfig, sinceTimestamp, limit)
        }

        val dataType = mapToDataType(typeId)
        if (dataType == null) {
            logger("[$typeId] mapToDataType returned null — unknown type")
            return@withContext emptyList()
        }
        if (healthDataStore == null) withContext(Dispatchers.Main) { connect() }
        val store = healthDataStore
        if (store == null) {
            logger("[$typeId] healthDataStore is null after connect attempt")
            return@withContext emptyList()
        }

        try {
            logger("[$typeId] Building read request (sinceTimestamp=$sinceTimestamp, limit=$limit, dataType=${dataType.javaClass.simpleName})")
            val request = buildReadRequest(dataType, sinceTimestamp, limit)
            if (request == null) {
                logger("[$typeId] buildReadRequest returned null — reflection failed")
                return@withContext emptyList()
            }
            val response = store.readData(request)
            logger("[$typeId] Raw SDK response: ${response.dataList.size} data points")
            val records = response.dataList.mapNotNull { dp ->
                val parsed = parseDataPoint(typeId, dp as HealthDataPoint)
                if (parsed == null) {
                    logger("[$typeId] parseDataPoint returned null for uid=${dp.uid}")
                } else {
                    logger("[$typeId] Parsed record uid=${parsed.uid}, fields=${parsed.fields}")
                }
                parsed
            }
            logger("[$typeId] After parsing: ${records.size} records (dropped ${response.dataList.size - records.size})")
            records
        } catch (e: Exception) {
            logger("[$typeId] Failed to read: ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
    }

    private data class AggregateConfig(
        val dataType: DataType,
        val fieldName: String,
        val aggregateOpName: String
    )

    private fun getAggregateConfig(typeId: String): AggregateConfig? = when (typeId) {
        "steps" -> AggregateConfig(DataTypes.STEPS, "TOTAL", "TOTAL")
        "activeEnergy" -> AggregateConfig(DataTypes.ACTIVITY_SUMMARY, "TOTAL_CALORIES", "TOTAL_CALORIES")
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun readAggregateData(
        typeId: String,
        config: AggregateConfig,
        sinceTimestamp: Long?,
        limit: Int
    ): List<HealthDataRecord> {
        if (healthDataStore == null) withContext(Dispatchers.Main) { connect() }
        val store = healthDataStore
        if (store == null) {
            logger("[$typeId] healthDataStore is null")
            return emptyList()
        }

        try {
            val aggregateOp = findAggregateOp(typeId, config.dataType, config.aggregateOpName)
            if (aggregateOp == null) {
                logger("[$typeId] Could not find aggregate operation '${config.aggregateOpName}'")
                return emptyList()
            }
            logger("[$typeId] Got aggregate op: ${aggregateOp.javaClass.name}")

            val builderGetter = aggregateOp.javaClass.methods.find {
                it.name == "getRequestBuilder" || it.name == "requestBuilder"
            }
            if (builderGetter == null) {
                logger("[$typeId] No requestBuilder on aggregate op. Methods: ${aggregateOp.javaClass.methods.map { it.name }}")
                return emptyList()
            }
            val builder = builderGetter.invoke(aggregateOp)
            if (builder == null) {
                logger("[$typeId] requestBuilder returned null")
                return emptyList()
            }
            val builderClass = builder.javaClass
            logger("[$typeId] Got builder: ${builderClass.name}")

            // Time filter
            val startTime = if (sinceTimestamp != null) {
                LocalDateTime.ofInstant(Instant.ofEpochMilli(sinceTimestamp + 1), ZoneId.systemDefault())
            } else {
                LocalDateTime.now().minusDays(30)
            }
            val endTime = LocalDateTime.now()
            val timeFilter = LocalTimeFilter.of(startTime, endTime)

            var hasGrouping = false
            try {
                val groupClass = Class.forName("com.samsung.android.sdk.health.data.request.LocalTimeGroup")
                val groupUnitClass = Class.forName("com.samsung.android.sdk.health.data.request.LocalTimeGroupUnit")
                val hourlyUnit = groupUnitClass.enumConstants?.find { (it as Enum<*>).name == "HOURLY" }
                if (hourlyUnit != null) {
                    val groupOfMethod = groupClass.getMethod("of", groupUnitClass, Int::class.java)
                    val timeGroup = groupOfMethod.invoke(null, hourlyUnit, 1)
                    builderClass.getMethod("setLocalTimeFilterWithGroup", LocalTimeFilter::class.java, groupClass)
                        .invoke(builder, timeFilter, timeGroup)
                    hasGrouping = true
                    logger("[$typeId] Applied hourly time grouping")
                }
            } catch (e: Exception) {
                logger("[$typeId] Hourly grouping failed: ${e.javaClass.simpleName}: ${e.message}")
            }

            if (!hasGrouping) {
                try {
                    builderClass.getMethod("setLocalTimeFilter", LocalTimeFilter::class.java)
                        .invoke(builder, timeFilter)
                    logger("[$typeId] Applied simple time filter")
                } catch (e: Exception) {
                    logger("[$typeId] setLocalTimeFilter failed: ${e.message}")
                }
            }

            try {
                builderClass.getMethod("setOrdering", Ordering::class.java).invoke(builder, Ordering.ASC)
            } catch (_: Exception) {}

            val request = builderClass.getMethod("build").invoke(builder) as AggregateRequest<Any>
            logger("[$typeId] Built aggregate request: ${request.javaClass.name}")

            val response = store.aggregateData(request)
            val dataList = response.dataList
            logger("[$typeId] Aggregate response: ${dataList.size} items")

            return dataList.mapNotNull { item -> parseAggregateItem(typeId, config.fieldName, item as Any) }
        } catch (e: Exception) {
            logger("[$typeId] Aggregate read failed: ${e.javaClass.simpleName}: ${e.message}")
            return emptyList()
        }
    }

    private fun findAggregateOp(typeId: String, dataType: DataType, opName: String): Any? {
        // Try 1: Companion getter (e.g. Companion.getTOTAL())
        try {
            val companionField = dataType.javaClass.getField("Companion")
            val companion = companionField.get(dataType)
            val getter = companion.javaClass.methods.find { it.name == "get$opName" }
            if (getter != null) {
                val op = getter.invoke(companion)
                if (op != null) {
                    logger("[$typeId] Got $opName via Companion.get$opName()")
                    return op
                }
            }
        } catch (e: Exception) {
            logger("[$typeId] Companion.$opName failed: ${e.message}")
        }

        // Try 2: static field
        try {
            val field = dataType.javaClass.getField(opName)
            val op = field.get(dataType)
            if (op != null) {
                logger("[$typeId] Got $opName via static field")
                return op
            }
        } catch (e: Exception) {
            logger("[$typeId] Static field $opName failed: ${e.message}")
        }

        // Try 3: getAllAggregateOperations — match by name or pick first
        try {
            val allOps = dataType.javaClass.getMethod("getAllAggregateOperations").invoke(dataType)
            if (allOps is Collection<*> && allOps.isNotEmpty()) {
                logger("[$typeId] Found ${allOps.size} aggregate operations")
                for (op in allOps) {
                    val name = try { op?.javaClass?.getMethod("getName")?.invoke(op)?.toString() } catch (_: Exception) { null }
                    logger("[$typeId]   op: ${op?.javaClass?.simpleName}, name=$name")
                    if (name != null && name.equals(opName, ignoreCase = true)) {
                        logger("[$typeId] Matched op by name: $name")
                        return op
                    }
                }
                logger("[$typeId] No name match, using first op")
                return allOps.first()
            }
        } catch (e: Exception) {
            logger("[$typeId] getAllAggregateOperations failed: ${e.message}")
        }

        return null
    }

    private fun parseAggregateItem(typeId: String, fieldName: String, item: Any?): HealthDataRecord? {
        if (item == null) return null
        try {
            val methods = item.javaClass.methods.associate { it.name to it }

            val startTime = (methods["getStartTime"] ?: methods["getStartLocalDateTime"])?.invoke(item)
            val endTime = (methods["getEndTime"] ?: methods["getEndLocalDateTime"])?.invoke(item)

            val startMs = when (startTime) {
                is java.time.Instant -> startTime.toEpochMilli()
                is LocalDateTime -> startTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                is Number -> startTime.toLong()
                else -> null
            }
            val endMs = when (endTime) {
                is java.time.Instant -> endTime.toEpochMilli()
                is LocalDateTime -> endTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                is Number -> endTime.toLong()
                else -> null
            }

            val value = methods["getValue"]?.invoke(item)
            val numericValue = when (value) {
                is Number -> value.toDouble()
                else -> null
            }

            logger("[$typeId] Aggregate item: startMs=$startMs, endMs=$endMs, value=$numericValue (type=${value?.javaClass?.simpleName})")
            if (startMs == null || numericValue == null || numericValue == 0.0) return null

            return HealthDataRecord(
                uid = UUID.randomUUID().toString(),
                dataType = typeId.uppercase(),
                startTime = startMs,
                endTime = endMs ?: startMs,
                dataSource = RawDataSource(null, null),
                device = DeviceInfo(
                    deviceId = null, manufacturer = Build.MANUFACTURER, model = Build.MODEL,
                    name = Build.DEVICE, brand = Build.BRAND, product = Build.PRODUCT,
                    osType = "Android", osVersion = Build.VERSION.RELEASE,
                    sdkVersion = Build.VERSION.SDK_INT, deviceType = "MOBILE", isSourceDevice = false
                ),
                fields = mapOf(fieldName to numericValue)
            )
        } catch (e: Exception) {
            logger("[$typeId] Failed to parse aggregate item: ${e.javaClass.simpleName}: ${e.message}")
            logger("[$typeId] Item class: ${item.javaClass.name}, methods: ${item.javaClass.methods.map { it.name }}")
            return null
        }
    }

    // -----------------------------------------------------------------------
    // Samsung → Unified conversion
    // -----------------------------------------------------------------------

    private fun convertToUnified(typeId: String, rawRecords: List<HealthDataRecord>): ProviderReadResult {
        val records = mutableListOf<UnifiedRecord>()
        val workouts = mutableListOf<UnifiedWorkout>()
        val sleepEntries = mutableListOf<UnifiedSleep>()
        var maxTimestamp: Long? = null

        for (raw in rawRecords) {
            val ts = raw.endTime ?: raw.startTime
            if (maxTimestamp == null || ts > maxTimestamp) maxTimestamp = ts

            when (typeId) {
                "workout" -> convertWorkout(raw)?.let { workouts.addAll(it) }
                "sleep" -> convertSleep(raw)?.let { sleepEntries.addAll(it) }
                else -> convertRecords(typeId, raw)?.let { records.addAll(it) }
            }
        }

        return ProviderReadResult(
            data = UnifiedHealthData(records, workouts, sleepEntries),
            maxTimestamp = maxTimestamp
        )
    }

    // ---- records ----

    private fun convertRecords(typeId: String, raw: HealthDataRecord): List<UnifiedRecord>? {
        val source = buildUnifiedSource(raw)
        val zoneOffset = UnifiedTimestamp.zoneOffsetString()
        val startDate = UnifiedTimestamp.fromEpochMs(raw.startTime)
        val endDate = UnifiedTimestamp.fromEpochMs(raw.endTime ?: raw.startTime)

        return when (typeId) {
            "heartRate" -> {
                val hr = (raw.fields["HEART_RATE"] as? Number)?.toDouble() ?: return null
                listOf(UnifiedRecord(raw.uid, "HEART_RATE", startDate, endDate, zoneOffset, source, hr, "bpm", null, null))
            }
            "steps" -> {
                val totalRaw = raw.fields["TOTAL"]
                val total = (totalRaw as? Number)?.toDouble()
                logger("[steps] convertRecords: uid=${raw.uid}, TOTAL raw=$totalRaw (type=${totalRaw?.javaClass?.simpleName}), converted=$total, allFields=${raw.fields}")
                if (total == null) return null
                listOf(UnifiedRecord(raw.uid, "STEP_COUNT", startDate, endDate, zoneOffset, source, total, "count", null, null))
            }
            "oxygenSaturation" -> {
                val spo2 = (raw.fields["OXYGEN_SATURATION"] as? Number)?.toDouble() ?: return null
                listOf(UnifiedRecord(raw.uid, "OXYGEN_SATURATION", startDate, endDate, zoneOffset, source, spo2, "%", null, null))
            }
            "bloodGlucose" -> {
                val mgDl = (raw.fields["LEVEL"] as? Number)?.toDouble() ?: return null
                val mmol = Math.round(mgDl / 18.0182 * 100.0) / 100.0
                val meta = mutableMapOf<String, Any?>()
                raw.fields["MEAL_STATUS"]?.let { meta["mealStatus"] = it.toString() }
                raw.fields["MEASUREMENT_TYPE"]?.let { meta["measurementType"] = it.toString() }
                raw.fields["SAMPLE_SOURCE_TYPE"]?.let { meta["sampleSourceType"] = it.toString() }
                listOf(UnifiedRecord(raw.uid, "BLOOD_GLUCOSE", startDate, endDate, zoneOffset, source, mmol, "mmol/L", null, meta.ifEmpty { null }))
            }
            "bloodPressure", "bloodPressureSystolic", "bloodPressureDiastolic" -> {
                val results = mutableListOf<UnifiedRecord>()
                (raw.fields["SYSTOLIC"] as? Number)?.let {
                    results.add(UnifiedRecord("${raw.uid}-sys", "BLOOD_PRESSURE_SYSTOLIC", startDate, endDate, zoneOffset, source, it.toDouble(), "mmHg", raw.uid, null))
                }
                (raw.fields["DIASTOLIC"] as? Number)?.let {
                    results.add(UnifiedRecord("${raw.uid}-dia", "BLOOD_PRESSURE_DIASTOLIC", startDate, endDate, zoneOffset, source, it.toDouble(), "mmHg", raw.uid, null))
                }
                (raw.fields["PULSE"] as? Number)?.let {
                    results.add(UnifiedRecord("${raw.uid}-pulse", "HEART_RATE", startDate, endDate, zoneOffset, source, it.toDouble(), "bpm", raw.uid, null))
                }
                results.ifEmpty { null }
            }
            "bodyTemperature" -> {
                val temp = (raw.fields["TEMPERATURE"] as? Number)?.toDouble() ?: return null
                listOf(UnifiedRecord(raw.uid, "BODY_TEMPERATURE", startDate, endDate, zoneOffset, source, temp, "°C", null, null))
            }
            "flightsClimbed" -> {
                val floors = (raw.fields["FLOORS"] as? Number)?.toDouble() ?: return null
                listOf(UnifiedRecord(raw.uid, "FLOORS_CLIMBED", startDate, endDate, zoneOffset, source, floors, "count", null, null))
            }
            "bodyMass", "bodyFatPercentage", "leanBodyMass", "height", "bmi" -> convertBodyComposition(raw, source, startDate, endDate, zoneOffset)
            "water" -> {
                val vol = (raw.fields["VOLUME"] as? Number)?.toDouble() ?: return null
                listOf(UnifiedRecord(raw.uid, "HYDRATION", startDate, endDate, zoneOffset, source, vol, "mL", null, null))
            }
            "activeEnergy" -> {
                val cal = (raw.fields["TOTAL_CALORIES"] as? Number)?.toDouble() ?: return null
                listOf(UnifiedRecord(raw.uid, "ACTIVE_CALORIES_BURNED", startDate, endDate, zoneOffset, source, cal, "kcal", null, null))
            }
            else -> null
        }
    }

    private fun convertBodyComposition(
        raw: HealthDataRecord,
        source: UnifiedSource,
        startDate: String,
        endDate: String,
        zoneOffset: String?
    ): List<UnifiedRecord> {
        val results = mutableListOf<UnifiedRecord>()
        val parentId = raw.uid

        (raw.fields["WEIGHT"] as? Number)?.let {
            results.add(UnifiedRecord("$parentId-weight", "WEIGHT", startDate, endDate, zoneOffset, source, it.toDouble(), "kg", parentId, null))
        }
        (raw.fields["HEIGHT"] as? Number)?.let {
            results.add(UnifiedRecord("$parentId-height", "HEIGHT", startDate, endDate, zoneOffset, source, it.toDouble() / 100.0, "m", parentId, null))
        }
        (raw.fields["BODY_FAT"] as? Number)?.let {
            results.add(UnifiedRecord("$parentId-bf", "BODY_FAT", startDate, endDate, zoneOffset, source, it.toDouble(), "%", parentId, null))
        }
        (raw.fields["BODY_FAT_MASS"] as? Number)?.let {
            results.add(UnifiedRecord("$parentId-bfm", "BODY_FAT_MASS", startDate, endDate, zoneOffset, source, it.toDouble(), "kg", parentId, null))
        }
        (raw.fields["FAT_FREE_MASS"] as? Number)?.let {
            results.add(UnifiedRecord("$parentId-ffm", "LEAN_BODY_MASS", startDate, endDate, zoneOffset, source, it.toDouble(), "kg", parentId, null))
        }
        (raw.fields["SKELETAL_MUSCLE_MASS"] as? Number)?.let {
            results.add(UnifiedRecord("$parentId-smm", "SKELETAL_MUSCLE_MASS", startDate, endDate, zoneOffset, source, it.toDouble(), "kg", parentId, null))
        }
        (raw.fields["BMI"] as? Number)?.let {
            results.add(UnifiedRecord("$parentId-bmi", "BMI", startDate, endDate, zoneOffset, source, it.toDouble(), "kg/m²", parentId, null))
        }
        (raw.fields["BASAL_METABOLIC_RATE"] as? Number)?.let {
            results.add(UnifiedRecord("$parentId-bmr", "BASAL_METABOLIC_RATE", startDate, endDate, zoneOffset, source, it.toDouble(), "kcal/day", parentId, null))
        }
        return results
    }

    // ---- workouts ----

    @Suppress("UNCHECKED_CAST")
    private fun convertWorkout(raw: HealthDataRecord): List<UnifiedWorkout>? {
        val source = buildUnifiedSource(raw)
        val zoneOffset = UnifiedTimestamp.zoneOffsetString()
        val exerciseType = raw.fields["EXERCISE_TYPE"]?.toString() ?: "UNKNOWN"

        val sessions = raw.fields["SESSIONS"] as? List<Map<String, Any?>> ?: emptyList()
        if (sessions.isEmpty()) {
            return listOf(buildWorkoutFromRaw(raw.uid, null, raw, source, zoneOffset, exerciseType, raw.fields))
        }

        return sessions.mapIndexed { idx, session ->
            val sessionStart = (session["startTime"] as? Number)?.toLong() ?: raw.startTime
            val sessionEnd = (session["endTime"] as? Number)?.toLong() ?: raw.endTime ?: raw.startTime
            val comment = session["comment"] as? String

            val workoutValues = mutableListOf<Map<String, Any>>()
            val samplesList = mutableListOf<Map<String, Any?>>()
            val routeList = mutableListOf<Map<String, Any?>>()

            for ((key, value) in session) {
                when (key) {
                    "startTime", "endTime", "comment", "swimmingLog" -> {}
                    "route" -> {
                        (value as? List<Map<String, Any?>>)?.forEach { pt ->
                            routeList.add(mapOf(
                                "timestamp" to (pt["timestamp"] as? Number)?.let { UnifiedTimestamp.fromEpochMs(it.toLong()) },
                                "latitude" to pt["latitude"],
                                "longitude" to pt["longitude"],
                                "altitudeM" to pt["altitude"],
                                "horizontalAccuracyM" to pt["accuracy"],
                                "verticalAccuracyM" to null
                            ))
                        }
                    }
                    "log" -> {
                        (value as? List<Map<String, Any?>>)?.forEach { entry ->
                            val ts = (entry["timestamp"] as? Number)?.let { UnifiedTimestamp.fromEpochMs(it.toLong()) }
                            entry["heartRate"]?.let { samplesList.add(mapOf("timestamp" to ts, "type" to "heartRate", "value" to it, "unit" to "bpm")) }
                            entry["cadence"]?.let { samplesList.add(mapOf("timestamp" to ts, "type" to "cadence", "value" to it, "unit" to "spm")) }
                            entry["speed"]?.let { samplesList.add(mapOf("timestamp" to ts, "type" to "speed", "value" to it, "unit" to "m/s")) }
                            entry["power"]?.let { samplesList.add(mapOf("timestamp" to ts, "type" to "power", "value" to it, "unit" to "W")) }
                        }
                    }
                    else -> {
                        if (value is Number) {
                            workoutValues.add(mapOf("type" to key, "value" to value, "unit" to inferWorkoutValueUnit(key)))
                        }
                    }
                }
            }

            UnifiedWorkout(
                id = "${raw.uid}-s$idx",
                parentId = raw.uid,
                type = exerciseType,
                startDate = UnifiedTimestamp.fromEpochMs(sessionStart),
                endDate = UnifiedTimestamp.fromEpochMs(sessionEnd),
                zoneOffset = zoneOffset,
                source = source,
                title = raw.fields["CUSTOM_TITLE"] as? String,
                notes = comment,
                values = workoutValues.ifEmpty { null },
                segments = null,
                laps = null,
                route = routeList.ifEmpty { null },
                samples = samplesList.ifEmpty { null },
                metadata = null
            )
        }
    }

    private fun buildWorkoutFromRaw(
        id: String,
        parentId: String?,
        raw: HealthDataRecord,
        source: UnifiedSource,
        zoneOffset: String?,
        exerciseType: String,
        fields: Map<String, Any?>
    ): UnifiedWorkout {
        val values = fields
            .filter { it.key != "SESSIONS" && it.key != "EXERCISE_TYPE" && it.key != "CUSTOM_TITLE" && it.value is Number }
            .map { (k, v) -> mapOf("type" to k, "value" to v as Any, "unit" to inferWorkoutValueUnit(k)) }

        return UnifiedWorkout(
            id = id,
            parentId = parentId,
            type = exerciseType,
            startDate = UnifiedTimestamp.fromEpochMs(raw.startTime),
            endDate = UnifiedTimestamp.fromEpochMs(raw.endTime ?: raw.startTime),
            zoneOffset = zoneOffset,
            source = source,
            title = fields["CUSTOM_TITLE"] as? String,
            notes = null,
            values = values.ifEmpty { null },
            segments = null,
            laps = null,
            route = null,
            samples = null,
            metadata = null
        )
    }

    private fun inferWorkoutValueUnit(key: String): String = when (key.lowercase()) {
        "calories", "totalcalories" -> "kcal"
        "distance" -> "m"
        "duration", "totalduration" -> "ms"
        "meanheartrate", "maxheartrate", "minheartrate" -> "bpm"
        "meanspeed", "maxspeed" -> "m/s"
        "meancadence", "maxcadence" -> "spm"
        "altitudegain", "altitudeloss", "maxaltitude", "minaltitude" -> "m"
        "vo2max" -> "mL/kg/min"
        else -> ""
    }

    // ---- sleep ----

    @Suppress("UNCHECKED_CAST")
    private fun convertSleep(raw: HealthDataRecord): List<UnifiedSleep>? {
        val source = buildUnifiedSource(raw)
        val zoneOffset = UnifiedTimestamp.zoneOffsetString()
        val sleepScore = raw.fields["SLEEP_SCORE"] as? Number
        val scoreValues = sleepScore?.let { listOf(mapOf("type" to "sleepScore", "value" to it, "unit" to "score")) }

        logger("Sleep record ${raw.uid}: fields=${raw.fields.keys}")

        val sessions = raw.fields["SESSIONS"] as? List<Map<String, Any?>> ?: emptyList()
        if (sessions.isEmpty()) {
            logger("Sleep record ${raw.uid}: no sessions, using top-level STAGE")
            val stage = (raw.fields["STAGE"]?.toString() ?: "UNKNOWN").let { mapSamsungSleepStage(it) }
            return listOf(UnifiedSleep(
                id = raw.uid,
                parentId = null,
                stage = stage,
                startDate = UnifiedTimestamp.fromEpochMs(raw.startTime),
                endDate = UnifiedTimestamp.fromEpochMs(raw.endTime ?: raw.startTime),
                zoneOffset = zoneOffset,
                source = source,
                values = scoreValues,
                metadata = null
            ))
        }

        logger("Sleep record ${raw.uid}: ${sessions.size} sessions")
        val results = mutableListOf<UnifiedSleep>()
        for ((sIdx, session) in sessions.withIndex()) {
            logger("  Session $sIdx keys: ${session.keys}")
            val stages = (session["sleepStages"] ?: session["stages"]) as? List<Map<String, Any?>>
            if (stages == null) {
                logger("  Session $sIdx: no stages found, creating single entry")
                val sessStart = (session["startTime"] as? Number)?.toLong() ?: raw.startTime
                val sessEnd = (session["endTime"] as? Number)?.toLong() ?: raw.endTime ?: raw.startTime
                results.add(UnifiedSleep(
                    id = "${raw.uid}-s$sIdx",
                    parentId = raw.uid,
                    stage = "sleeping",
                    startDate = UnifiedTimestamp.fromEpochMs(sessStart),
                    endDate = UnifiedTimestamp.fromEpochMs(sessEnd),
                    zoneOffset = zoneOffset,
                    source = source,
                    values = scoreValues,
                    metadata = null
                ))
                continue
            }
            logger("  Session $sIdx: ${stages.size} stages")
            for ((stIdx, stageMap) in stages.withIndex()) {
                val stageName = mapSamsungSleepStage(stageMap["stage"]?.toString() ?: "UNKNOWN")
                val stStart = (stageMap["startTime"] as? Number)?.toLong()
                val stEnd = (stageMap["endTime"] as? Number)?.toLong()
                if (stStart == null || stEnd == null) continue

                results.add(UnifiedSleep(
                    id = "${raw.uid}-s$sIdx-$stIdx",
                    parentId = raw.uid,
                    stage = stageName,
                    startDate = UnifiedTimestamp.fromEpochMs(stStart),
                    endDate = UnifiedTimestamp.fromEpochMs(stEnd),
                    zoneOffset = zoneOffset,
                    source = source,
                    values = scoreValues,
                    metadata = null
                ))
            }
        }
        return results.ifEmpty { null }
    }

    private fun mapSamsungSleepStage(stage: String): String = when (stage.uppercase()) {
        "AWAKE" -> "awake"
        "LIGHT" -> "light"
        "DEEP" -> "deep"
        "REM" -> "rem"
        else -> "unknown"
    }

    // ---- source helper ----

    private fun buildUnifiedSource(raw: HealthDataRecord): UnifiedSource = UnifiedSource(
        appId = raw.dataSource.appId,
        deviceId = raw.dataSource.deviceId,
        deviceName = raw.device.name,
        deviceManufacturer = raw.device.manufacturer,
        deviceModel = raw.device.model,
        deviceType = DeviceTypeMapper.fromSamsungDeviceType(raw.device.deviceType),
        recordingMethod = null
    )

    // -----------------------------------------------------------------------
    // Samsung SDK internals (unchanged logic)
    // -----------------------------------------------------------------------

    private fun mapToDataType(typeId: String): DataType? {
        return try {
            when (typeId) {
                "steps" -> DataTypes.STEPS
                "heartRate" -> DataTypes.HEART_RATE
                "sleep" -> DataTypes.SLEEP
                "workout" -> DataTypes.EXERCISE
                "oxygenSaturation" -> DataTypes.BLOOD_OXYGEN
                "bloodGlucose" -> DataTypes.BLOOD_GLUCOSE
                "bloodPressure", "bloodPressureSystolic", "bloodPressureDiastolic" -> DataTypes.BLOOD_PRESSURE
                "flightsClimbed" -> DataTypes.FLOORS_CLIMBED
                "bodyTemperature" -> DataTypes.BODY_TEMPERATURE
                "bodyMass", "bodyFatPercentage", "leanBodyMass", "height", "bmi" -> DataTypes.BODY_COMPOSITION
                "activeEnergy" -> DataTypes.ACTIVITY_SUMMARY
                "water" -> DataTypes.WATER_INTAKE
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun loadConnectedDevices() {
        val dm = deviceManager ?: return
        try {
            for (group in listOf(DeviceGroup.MOBILE, DeviceGroup.WATCH, DeviceGroup.RING, DeviceGroup.BAND, DeviceGroup.ACCESSORY)) {
                try {
                    dm.getDevices(group).forEach { device -> device.id?.let { deviceCache[it] = device } }
                } catch (_: Exception) {}
            }
            logger("Loaded ${deviceCache.size} devices from Samsung Health")
        } catch (e: Exception) {
            logger("Error loading devices: ${e.message}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildReadRequest(dataType: DataType, sinceTimestamp: Long?, limit: Int): ReadDataRequest<HealthDataPoint>? {
        val builder = getRequestBuilder(dataType, limit) ?: return null
        val builderClass = builder.javaClass

        try { builderClass.getMethod("setLimit", Int::class.java).invoke(builder, limit) } catch (e: Exception) { logger("[buildReadRequest] setLimit failed: ${e.message}") }
        try { builderClass.getMethod("setOrdering", Ordering::class.java).invoke(builder, Ordering.ASC) } catch (e: Exception) { logger("[buildReadRequest] setOrdering failed: ${e.message}") }

        if (sinceTimestamp != null) {
            try {
                val startTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(sinceTimestamp + 1), ZoneId.systemDefault())
                val filter = LocalTimeFilter.since(startTime)
                builderClass.getMethod("setLocalTimeFilter", LocalTimeFilter::class.java).invoke(builder, filter)
                logger("[buildReadRequest] Applied time filter since=$startTime")
            } catch (e: Exception) { logger("[buildReadRequest] setLocalTimeFilter failed: ${e.message}") }
        }

        return try {
            val result = builderClass.getMethod("build").invoke(builder) as? ReadDataRequest<HealthDataPoint>
            logger("[buildReadRequest] Result: ${if (result != null) "OK" else "null (cast failed)"}")
            result
        } catch (e: Exception) {
            logger("[buildReadRequest] build FAILED: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun getRequestBuilder(dataType: DataType, limit: Int): Any? {
        try {
            val builderMethod = dataType.javaClass.getMethod("getReadDataRequestBuilder")
            val builder = builderMethod.invoke(dataType)
            if (builder != null) {
                logger("[buildReadRequest] Got builder via dataType.getReadDataRequestBuilder()")
                return builder
            }
        } catch (_: NoSuchMethodException) {
            logger("[buildReadRequest] No getReadDataRequestBuilder on ${dataType.javaClass.simpleName}, trying ReadDataRequest.Builder()")
        } catch (e: Exception) {
            logger("[buildReadRequest] getReadDataRequestBuilder failed: ${e.message}, trying ReadDataRequest.Builder()")
        }

        return try {
            val rdClass = ReadDataRequest::class.java
            val builderClasses = (rdClass.declaredClasses + rdClass.classes)
                .filter { !it.isInterface && it.simpleName != "Companion" }
                .distinctBy { it.simpleName }

            for (bc in builderClasses) {
                for (c in bc.constructors) {
                    logger("[buildReadRequest] ${bc.simpleName} constructor: (${c.parameterTypes.map { it.simpleName }.joinToString(", ")})")
                }
                for (m in bc.declaredMethods) {
                    logger("[buildReadRequest] ${bc.simpleName}.${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString(", ")}) -> ${m.returnType.simpleName}")
                }
            }

            val localDateBuilder = builderClasses.find { it.simpleName == "LocalDateBuilder" }
            if (localDateBuilder != null) {
                val constructor = localDateBuilder.constructors.firstOrNull()
                if (constructor != null) {
                    constructor.isAccessible = true
                    val paramTypes = constructor.parameterTypes
                    logger("[buildReadRequest] Trying LocalDateBuilder with ${paramTypes.size} params: ${paramTypes.map { it.simpleName }}")
                    val args = paramTypes.map { pType ->
                        when {
                            DataType::class.java.isAssignableFrom(pType) -> dataType
                            pType == Int::class.java || pType == Integer::class.java -> limit
                            pType == Ordering::class.java -> Ordering.ASC
                            else -> null
                        }
                    }.toTypedArray()
                    logger("[buildReadRequest] Constructed args: ${args.map { it?.javaClass?.simpleName ?: "null" }}")
                    val builder = constructor.newInstance(*args)
                    logger("[buildReadRequest] Got builder via LocalDateBuilder")
                    return builder
                }
            }

            logger("[buildReadRequest] Could not create any builder for ${dataType.javaClass.simpleName}")
            null
        } catch (e: Exception) {
            logger("[buildReadRequest] Fallback FAILED: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun parseDataPoint(typeId: String, dataPoint: HealthDataPoint): HealthDataRecord? {
        return try {
            val uid = dataPoint.uid ?: UUID.randomUUID().toString()
            val source = dataPoint.dataSource
            HealthDataRecord(
                uid = uid,
                dataType = getDataTypeName(typeId),
                startTime = dataPoint.startTime.toEpochMilli(),
                endTime = dataPoint.endTime?.toEpochMilli(),
                dataSource = RawDataSource(source?.appId, source?.deviceId),
                device = getDeviceInfo(source),
                fields = extractFieldsForType(typeId, dataPoint)
            )
        } catch (e: Exception) {
            logger("Failed to parse $typeId record: ${e.message}")
            null
        }
    }

    private fun getDeviceInfo(source: DataSource?): DeviceInfo {
        val deviceId = source?.deviceId
        val cachedDevice = deviceId?.let { deviceCache[it] }

        if (cachedDevice != null) {
            return DeviceInfo(
                deviceId = deviceId,
                manufacturer = cachedDevice.manufacturer ?: "Unknown",
                model = cachedDevice.model ?: "Unknown",
                name = cachedDevice.name ?: cachedDevice.model ?: "Unknown",
                brand = cachedDevice.manufacturer ?: "Unknown",
                product = cachedDevice.model ?: "Unknown",
                osType = "Android",
                osVersion = "",
                sdkVersion = 0,
                deviceType = getDeviceGroup(cachedDevice),
                isSourceDevice = true
            )
        }

        return DeviceInfo(
            deviceId = deviceId,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            name = Build.DEVICE,
            brand = Build.BRAND,
            product = Build.PRODUCT,
            osType = "Android",
            osVersion = Build.VERSION.RELEASE,
            sdkVersion = Build.VERSION.SDK_INT,
            deviceType = "MOBILE",
            isSourceDevice = false
        )
    }

    private fun getDeviceGroup(device: Device): String {
        return try {
            val groupMethod = device.javaClass.methods.find { it.name == "getGroup" || it.name == "getDeviceGroup" }
            val group = groupMethod?.invoke(device)
            when {
                group is DeviceGroup -> group.name
                group?.toString()?.contains("WATCH", ignoreCase = true) == true -> "WATCH"
                group?.toString()?.contains("RING", ignoreCase = true) == true -> "RING"
                group?.toString()?.contains("BAND", ignoreCase = true) == true -> "BAND"
                else -> "MOBILE"
            }
        } catch (_: Exception) { "UNKNOWN" }
    }

    private fun getDataTypeName(typeId: String): String = when (typeId) {
        "steps" -> "STEPS"
        "heartRate" -> "HEART_RATE"
        "oxygenSaturation" -> "BLOOD_OXYGEN"
        "bloodGlucose" -> "BLOOD_GLUCOSE"
        "bloodPressure", "bloodPressureSystolic", "bloodPressureDiastolic" -> "BLOOD_PRESSURE"
        "bodyTemperature" -> "BODY_TEMPERATURE"
        "flightsClimbed" -> "FLOORS_CLIMBED"
        "bodyMass", "bodyFatPercentage", "leanBodyMass", "height", "bmi" -> "BODY_COMPOSITION"
        "activeEnergy" -> "ACTIVITY_SUMMARY"
        "water" -> "WATER_INTAKE"
        "sleep" -> "SLEEP"
        "workout" -> "EXERCISE"
        else -> typeId.uppercase()
    }

    private fun extractFieldsForType(typeId: String, dataPoint: HealthDataPoint): Map<String, Any?> = when (typeId) {
        "heartRate" -> extractHeartRateFields(dataPoint)
        "steps" -> extractStepsFields(dataPoint)
        "oxygenSaturation" -> extractBloodOxygenFields(dataPoint)
        "bloodGlucose" -> extractBloodGlucoseFields(dataPoint)
        "bloodPressure", "bloodPressureSystolic", "bloodPressureDiastolic" -> extractBloodPressureFields(dataPoint)
        "bodyTemperature" -> extractBodyTemperatureFields(dataPoint)
        "flightsClimbed" -> extractFloorsClimbedFields(dataPoint)
        "bodyMass", "bodyFatPercentage", "leanBodyMass", "height", "bmi" -> extractBodyCompositionFields(dataPoint)
        "water" -> extractWaterIntakeFields(dataPoint)
        "workout" -> extractExerciseFields(dataPoint)
        "sleep" -> extractSleepFields(dataPoint)
        else -> emptyMap()
    }

    // ---- field extractors ----

    private fun extractHeartRateFields(dp: HealthDataPoint): Map<String, Any?> {
        val f = mutableMapOf<String, Any?>()
        getFieldValue<Float>(DataTypes.HEART_RATE, "HEART_RATE", dp)?.let { f["HEART_RATE"] = it }
        getFieldValue<Any>(DataTypes.HEART_RATE, "HEART_RATE_STATUS", dp)?.let { f["HEART_RATE_STATUS"] = if (it is Enum<*>) it.name else it.toString() }
        return f
    }

    private fun extractStepsFields(dp: HealthDataPoint): Map<String, Any?> {
        val f = mutableMapOf<String, Any?>()
        val totalLong = getFieldValue<Long>(DataTypes.STEPS, "TOTAL", dp)
        val totalInt = getFieldValue<Int>(DataTypes.STEPS, "TOTAL", dp)
        val totalFloat = getFieldValue<Float>(DataTypes.STEPS, "TOTAL", dp)
        val totalAny = getFieldValue<Any>(DataTypes.STEPS, "TOTAL", dp)
        logger("[steps] extractStepsFields: TOTAL as Long=$totalLong, Int=$totalInt, Float=$totalFloat, Any=$totalAny (type=${totalAny?.javaClass?.simpleName})")
        if (totalLong != null) {
            f["TOTAL"] = totalLong
        } else if (totalAny is Number) {
            logger("[steps] TOTAL fallback via Number: ${totalAny.toLong()}")
            f["TOTAL"] = totalAny.toLong()
        }
        return f
    }

    private fun extractBloodOxygenFields(dp: HealthDataPoint): Map<String, Any?> {
        val f = mutableMapOf<String, Any?>()
        getFieldValue<Float>(DataTypes.BLOOD_OXYGEN, "OXYGEN_SATURATION", dp)?.let { f["OXYGEN_SATURATION"] = it }
        return f
    }

    private fun extractBloodGlucoseFields(dp: HealthDataPoint): Map<String, Any?> {
        val f = mutableMapOf<String, Any?>()
        getFieldValue<Float>(DataTypes.BLOOD_GLUCOSE, "LEVEL", dp)?.let { f["LEVEL"] = it }
        getFieldValue<Any>(DataTypes.BLOOD_GLUCOSE, "MEAL_STATUS", dp)?.let { f["MEAL_STATUS"] = if (it is Enum<*>) it.name else it.toString() }
        getFieldValue<Any>(DataTypes.BLOOD_GLUCOSE, "MEASUREMENT_TYPE", dp)?.let { f["MEASUREMENT_TYPE"] = if (it is Enum<*>) it.name else it.toString() }
        getFieldValue<Any>(DataTypes.BLOOD_GLUCOSE, "SAMPLE_SOURCE_TYPE", dp)?.let { f["SAMPLE_SOURCE_TYPE"] = if (it is Enum<*>) it.name else it.toString() }
        return f
    }

    private fun extractBloodPressureFields(dp: HealthDataPoint): Map<String, Any?> {
        val f = mutableMapOf<String, Any?>()
        getFieldValue<Float>(DataTypes.BLOOD_PRESSURE, "SYSTOLIC", dp)?.let { f["SYSTOLIC"] = it }
        getFieldValue<Float>(DataTypes.BLOOD_PRESSURE, "DIASTOLIC", dp)?.let { f["DIASTOLIC"] = it }
        getFieldValue<Float>(DataTypes.BLOOD_PRESSURE, "PULSE", dp)?.let { f["PULSE"] = it }
        return f
    }

    private fun extractBodyTemperatureFields(dp: HealthDataPoint): Map<String, Any?> {
        val f = mutableMapOf<String, Any?>()
        getFieldValue<Float>(DataTypes.BODY_TEMPERATURE, "TEMPERATURE", dp)?.let { f["TEMPERATURE"] = it }
        return f
    }

    private fun extractFloorsClimbedFields(dp: HealthDataPoint): Map<String, Any?> {
        val f = mutableMapOf<String, Any?>()
        getFieldValue<Int>(DataTypes.FLOORS_CLIMBED, "FLOORS", dp)?.let { f["FLOORS"] = it }
        return f
    }

    private fun extractBodyCompositionFields(dp: HealthDataPoint): Map<String, Any?> {
        val f = mutableMapOf<String, Any?>()
        getFieldValue<Float>(DataTypes.BODY_COMPOSITION, "WEIGHT", dp)?.let { f["WEIGHT"] = it }
        getFieldValue<Float>(DataTypes.BODY_COMPOSITION, "HEIGHT", dp)?.let { f["HEIGHT"] = it }
        getFieldValue<Float>(DataTypes.BODY_COMPOSITION, "BODY_FAT", dp)?.let { f["BODY_FAT"] = it }
        getFieldValue<Float>(DataTypes.BODY_COMPOSITION, "BODY_FAT_MASS", dp)?.let { f["BODY_FAT_MASS"] = it }
        getFieldValue<Float>(DataTypes.BODY_COMPOSITION, "FAT_FREE_MASS", dp)?.let { f["FAT_FREE_MASS"] = it }
        getFieldValue<Float>(DataTypes.BODY_COMPOSITION, "SKELETAL_MUSCLE_MASS", dp)?.let { f["SKELETAL_MUSCLE_MASS"] = it }
        getFieldValue<Float>(DataTypes.BODY_COMPOSITION, "BMI", dp)?.let { f["BMI"] = it }
        getFieldValue<Float>(DataTypes.BODY_COMPOSITION, "BASAL_METABOLIC_RATE", dp)?.let { f["BASAL_METABOLIC_RATE"] = it }
        return f
    }

    private fun extractWaterIntakeFields(dp: HealthDataPoint): Map<String, Any?> {
        val f = mutableMapOf<String, Any?>()
        getFieldValue<Float>(DataTypes.WATER_INTAKE, "VOLUME", dp)?.let { f["VOLUME"] = it }
        return f
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractExerciseFields(dp: HealthDataPoint): Map<String, Any?> {
        val f = mutableMapOf<String, Any?>()
        getFieldValue<Any>(DataTypes.EXERCISE, "EXERCISE_TYPE", dp)?.let { f["EXERCISE_TYPE"] = if (it is Enum<*>) it.name else it.toString() }
        getFieldValue<Float>(DataTypes.EXERCISE, "TOTAL_CALORIES", dp)?.let { f["TOTAL_CALORIES"] = it }
        getFieldValue<Long>(DataTypes.EXERCISE, "TOTAL_DURATION", dp)?.let { f["TOTAL_DURATION"] = it }
        getFieldValue<String>(DataTypes.EXERCISE, "CUSTOM_TITLE", dp)?.let { f["CUSTOM_TITLE"] = it }
        getFieldValue<List<*>>(DataTypes.EXERCISE, "SESSIONS", dp)?.let { sessions ->
            f["SESSIONS"] = sessions.mapNotNull { extractSession(it) }
        }
        return f
    }

    private fun extractSession(session: Any?): Map<String, Any?>? {
        if (session == null) return null
        val data = mutableMapOf<String, Any?>()
        try {
            session.javaClass.methods
                .filter { it.parameterCount == 0 && it.name.startsWith("get") && it.name != "getClass" }
                .forEach { method ->
                    try {
                        val value = method.invoke(session) ?: return@forEach
                        val key = method.name.removePrefix("get").let { it.first().lowercase() + it.drop(1) }
                        when (value) {
                            is Number -> data[key] = value
                            is String -> if (value.isNotEmpty()) data[key] = value
                            is Boolean -> data[key] = value
                            is Enum<*> -> data[key] = value.name
                            is java.time.Instant -> data[key] = value.toEpochMilli()
                            is java.time.Duration -> data[key] = value.toMillis()
                        }
                    } catch (_: Exception) {}
                }
        } catch (_: Exception) {}
        return data.ifEmpty { null }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractSleepFields(dp: HealthDataPoint): Map<String, Any?> {
        val f = mutableMapOf<String, Any?>()
        getFieldValue<Any>(DataTypes.SLEEP, "STAGE", dp)?.let { f["STAGE"] = if (it is Enum<*>) it.name else it.toString() }
        getFieldValue<Float>(DataTypes.SLEEP, "EFFICIENCY", dp)?.let { f["EFFICIENCY"] = it }
        getFieldValue<Int>(DataTypes.SLEEP, "SLEEP_SCORE", dp)?.let { f["SLEEP_SCORE"] = it }
        getFieldValue<List<*>>(DataTypes.SLEEP, "SESSIONS", dp)?.let { sessions ->
            f["SESSIONS"] = sessions.mapNotNull { extractSleepSession(it) }
        }
        return f
    }

    private fun extractSleepSession(session: Any?): Map<String, Any?>? {
        if (session == null) return null
        val data = mutableMapOf<String, Any?>()
        try {
            session.javaClass.methods
                .filter { it.parameterCount == 0 && it.name.startsWith("get") && it.name != "getClass" }
                .forEach { method ->
                    try {
                        val value = method.invoke(session) ?: return@forEach
                        val key = method.name.removePrefix("get").let { it.first().lowercase() + it.drop(1) }
                        when (value) {
                            is Number -> data[key] = value
                            is String -> if (value.isNotEmpty()) data[key] = value
                            is Boolean -> data[key] = value
                            is Enum<*> -> data[key] = value.name
                            is java.time.Instant -> data[key] = value.toEpochMilli()
                            is java.time.Duration -> data[key] = value.toMillis()
                            is List<*> -> {
                                val stages = value.mapNotNull { extractSleepStage(it) }
                                if (stages.isNotEmpty()) data[key] = stages
                            }
                        }
                    } catch (_: Exception) {}
                }
        } catch (_: Exception) {}
        return data.ifEmpty { null }
    }

    private fun extractSleepStage(stage: Any?): Map<String, Any?>? {
        if (stage == null) return null
        val data = mutableMapOf<String, Any?>()
        try {
            stage.javaClass.methods
                .filter { it.parameterCount == 0 && it.name.startsWith("get") && it.name != "getClass" }
                .forEach { method ->
                    try {
                        val value = method.invoke(stage) ?: return@forEach
                        val key = method.name.removePrefix("get").let { it.first().lowercase() + it.drop(1) }
                        when (value) {
                            is Number -> data[key] = value
                            is Enum<*> -> data[key] = value.name
                            is java.time.Instant -> data[key] = value.toEpochMilli()
                            is java.time.Duration -> data[key] = value.toMillis()
                        }
                    } catch (_: Exception) {}
                }
        } catch (_: Exception) {}
        return data.ifEmpty { null }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getFieldValue(dataType: DataType, fieldName: String, dataPoint: HealthDataPoint): T? {
        return try {
            val field = dataType.javaClass.getField(fieldName).get(dataType)
            val getValueMethod = dataPoint.javaClass.getMethod("getValue", com.samsung.android.sdk.health.data.data.Field::class.java)
            getValueMethod.invoke(dataPoint, field) as? T
        } catch (_: Exception) { null }
    }
}

// ---------------------------------------------------------------------------
// Internal raw data models (Samsung-specific, not exposed via interface)
// ---------------------------------------------------------------------------

data class HealthDataRecord(
    val uid: String,
    val dataType: String,
    val startTime: Long,
    val endTime: Long?,
    val dataSource: RawDataSource,
    val device: DeviceInfo,
    val fields: Map<String, Any?>
)

data class RawDataSource(val appId: String?, val deviceId: String?)

data class DeviceInfo(
    val deviceId: String?,
    val manufacturer: String,
    val model: String,
    val name: String,
    val brand: String,
    val product: String,
    val osType: String,
    val osVersion: String,
    val sdkVersion: Int,
    val deviceType: String?,
    val isSourceDevice: Boolean = false
)
