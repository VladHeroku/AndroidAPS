package app.aaps.core.objects.meal

import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/**
 * Per-profile experimental protein/fat configuration for the non-actuating research preview.
 *
 * Coefficients are grams of delayed carbohydrate-equivalent per gram of macronutrient.
 * Defaults are zero on purpose: no insulin-relevant assumption is made until the user configures it.
 */
data class ProteinFatProfileConfig(
    val proteinCarbEqPerGram: Double = 0.0,
    val fatCarbEqPerGram: Double = 0.0,
    val dynamicLearnerEnabled: Boolean = false,
    val learnedProteinMultiplier: Double = 1.0,
    val learnedFatMultiplier: Double = 1.0,
    val learningConfidence: Double = 0.0
)

data class ProteinFatKineticPoint(
    val minutesFromMeal: Int,
    val carbEquivalentG: Double
)

data class ProteinFatShadowResult(
    val proteinG: Double,
    val fatG: Double,
    val effectiveProteinCoefficient: Double,
    val effectiveFatCoefficient: Double,
    val delayedCarbEquivalentG: Double,
    val advisoryInsulinU: Double,
    val dynamicIcrGPerU: Double,
    val kineticCurve: List<ProteinFatKineticPoint>,
    val dynamicLearnerApplied: Boolean,
    val advisoryOnly: Boolean = true
)

/**
 * A conservative delayed-meal shadow model.
 *
 * Literature supports a delayed 2-6 h glycemic effect of fat/protein but does not support one universal
 * conversion factor. The user therefore supplies the base coefficients. Optional learned multipliers
 * remain tightly bounded and confidence-weighted.
 */
object ProteinFatShadowModel {

    // 30-minute bins from 0 to 360 min. Each kernel sums to 1.
    // Protein: minimal early effect, dominant 1.5-5 h window.
    private val proteinKernel = doubleArrayOf(
        0.00, 0.00, 0.00, 0.05, 0.10, 0.16, 0.20, 0.18, 0.13, 0.09, 0.06, 0.03, 0.00
    )

    // Fat: later distribution, with most effect in the 2-6 h window.
    private val fatKernel = doubleArrayOf(
        0.00, 0.00, 0.00, 0.00, 0.04, 0.08, 0.13, 0.17, 0.19, 0.17, 0.12, 0.07, 0.03
    )

    fun estimate(
        proteinG: Double,
        fatG: Double,
        config: ProteinFatProfileConfig,
        dynamicIcrGPerU: Double
    ): ProteinFatShadowResult {
        require(dynamicIcrGPerU > 0.0) { "dynamicIcrGPerU must be > 0" }

        val confidence = config.learningConfidence.coerceIn(0.0, 1.0)
        val learnedProtein = if (config.dynamicLearnerEnabled)
            blendMultiplier(config.learnedProteinMultiplier, confidence) else 1.0
        val learnedFat = if (config.dynamicLearnerEnabled)
            blendMultiplier(config.learnedFatMultiplier, confidence) else 1.0

        val proteinCoeff = max(0.0, config.proteinCarbEqPerGram) * learnedProtein
        val fatCoeff = max(0.0, config.fatCarbEqPerGram) * learnedFat

        val proteinEq = max(0.0, proteinG) * proteinCoeff
        val fatEq = max(0.0, fatG) * fatCoeff
        val totalEq = proteinEq + fatEq

        val curve = proteinKernel.indices.map { i ->
            ProteinFatKineticPoint(
                minutesFromMeal = i * 30,
                carbEquivalentG = proteinEq * proteinKernel[i] + fatEq * fatKernel[i]
            )
        }

        return ProteinFatShadowResult(
            proteinG = max(0.0, proteinG),
            fatG = max(0.0, fatG),
            effectiveProteinCoefficient = proteinCoeff,
            effectiveFatCoefficient = fatCoeff,
            delayedCarbEquivalentG = totalEq,
            advisoryInsulinU = totalEq / dynamicIcrGPerU,
            dynamicIcrGPerU = dynamicIcrGPerU,
            kineticCurve = curve,
            dynamicLearnerApplied = config.dynamicLearnerEnabled && confidence > 0.0
        )
    }

    private fun blendMultiplier(raw: Double, confidence: Double): Double {
        val bounded = raw.coerceIn(0.70, 1.30)
        return 1.0 + (bounded - 1.0) * confidence
    }
}

/**
 * Very slow bounded learner for late meal outcomes. It is deliberately separated from pump actuation.
 *
 * Positive late error (late glucose above expected) increases the relevant multiplier; negative late
 * error decreases it. A single observation can move a multiplier by at most 3%.
 */
object ProteinFatAdaptiveLearner {

    data class Outcome(
        val proteinG: Double,
        val fatG: Double,
        val lateGlucoseErrorMgdl: Double,
        val cleanObservation: Boolean
    )

    fun update(config: ProteinFatProfileConfig, outcome: Outcome): ProteinFatProfileConfig {
        if (!config.dynamicLearnerEnabled || !outcome.cleanObservation) return config
        val macroTotal = outcome.proteinG.coerceAtLeast(0.0) + outcome.fatG.coerceAtLeast(0.0)
        if (macroTotal < 10.0) return config

        val step = (outcome.lateGlucoseErrorMgdl / 100.0).coerceIn(-0.03, 0.03)
        val proteinShare = if (macroTotal > 0) outcome.proteinG.coerceAtLeast(0.0) / macroTotal else 0.0
        val fatShare = 1.0 - proteinShare

        val newProtein = (config.learnedProteinMultiplier * (1.0 + step * proteinShare)).coerceIn(0.70, 1.30)
        val newFat = (config.learnedFatMultiplier * (1.0 + step * fatShare)).coerceIn(0.70, 1.30)
        val newConfidence = (config.learningConfidence + 0.04).coerceAtMost(1.0)

        return config.copy(
            learnedProteinMultiplier = newProtein,
            learnedFatMultiplier = newFat,
            learningConfidence = newConfidence
        )
    }
}

/**
 * Lightweight per-profile storage. Kept outside the clinical profile JSON so upstream AAPS profile
 * interoperability is unchanged.
 */
object ProteinFatProfileStore {

    fun load(preferences: Preferences, profileName: String): ProteinFatProfileConfig {
        val root = JSONObject(preferences.get(StringNonKey.MealMacroProfileConfig))
        val obj = root.optJSONObject(profileName) ?: return ProteinFatProfileConfig()
        return ProteinFatProfileConfig(
            proteinCarbEqPerGram = obj.optDouble("proteinCoeff", 0.0),
            fatCarbEqPerGram = obj.optDouble("fatCoeff", 0.0),
            dynamicLearnerEnabled = obj.optBoolean("dynamic", false),
            learnedProteinMultiplier = obj.optDouble("learnedProtein", 1.0),
            learnedFatMultiplier = obj.optDouble("learnedFat", 1.0),
            learningConfidence = obj.optDouble("confidence", 0.0)
        )
    }

    fun save(preferences: Preferences, profileName: String, config: ProteinFatProfileConfig) {
        val raw = preferences.get(StringNonKey.MealMacroProfileConfig)
        val root = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
        root.put(
            profileName,
            JSONObject()
                .put("proteinCoeff", config.proteinCarbEqPerGram)
                .put("fatCoeff", config.fatCarbEqPerGram)
                .put("dynamic", config.dynamicLearnerEnabled)
                .put("learnedProtein", config.learnedProteinMultiplier)
                .put("learnedFat", config.learnedFatMultiplier)
                .put("confidence", config.learningConfidence)
        )
        preferences.put(StringNonKey.MealMacroProfileConfig, root.toString())
    }
}

data class ProteinFatEntry(
    val timestamp: Long,
    val profileName: String,
    val proteinG: Double,
    val fatG: Double,
    val carbsG: Double = 0.0,
    val delayedCarbEquivalentG: Double = 0.0,
    val advisoryInsulinU: Double = 0.0,
    val dynamicIcrGPerU: Double = 0.0,
    val profileIcrGPerU: Double = 0.0,
    val profileIsfMgdlPerU: Double = 0.0,
    val preMealBgMgdl: Double = 0.0,
    val preMealTrendMgdlPer5m: Double = 0.0,
    val iobU: Double = 0.0,
    val autoIsfRatio: Double = 1.0,
    val recentTddRatio: Double = 1.0,
    val neuralIcrMultiplier: Double = 1.0,
    val neuralConfidence: Double = 0.0,
    val source: String = "manual",
    val processed: Boolean = false,
    val cleanEpisode: Boolean? = null,
    val lateGlucoseErrorMgdl: Double? = null,
    val hypoOccurred: Boolean? = null
)

object ProteinFatEntryStore {

    private const val MAX_HISTORY = 500

    fun setPending(preferences: Preferences, entry: ProteinFatEntry) {
        preferences.put(StringNonKey.MealMacroPending, toJson(entry).toString())
    }

    fun pending(preferences: Preferences, now: Long, maxAgeMs: Long = 2 * 60 * 60 * 1000L): ProteinFatEntry? {
        val raw = preferences.get(StringNonKey.MealMacroPending)
        if (raw.isBlank()) return null
        return try {
            val entry = fromJson(JSONObject(raw))
            if (now - entry.timestamp in 0..maxAgeMs) entry else null
        } catch (_: Exception) {
            null
        }
    }

    fun appendHistory(preferences: Preferences, entry: ProteinFatEntry) {
        val list = history(preferences).toMutableList()
        list.removeAll { it.timestamp == entry.timestamp && it.profileName == entry.profileName }
        list.add(entry)
        val arr = JSONArray()
        list.sortedBy { it.timestamp }.takeLast(MAX_HISTORY).forEach { arr.put(toJson(it)) }
        preferences.put(StringNonKey.MealMacroHistory, arr.toString())
    }

    fun update(preferences: Preferences, entry: ProteinFatEntry) = appendHistory(preferences, entry)

    fun history(preferences: Preferences): List<ProteinFatEntry> {
        val arr = try { JSONArray(preferences.get(StringNonKey.MealMacroHistory)) } catch (_: Exception) { JSONArray() }
        val out = ArrayList<ProteinFatEntry>()
        for (i in 0 until arr.length()) {
            try { out.add(fromJson(arr.getJSONObject(i))) } catch (_: Exception) {}
        }
        return out.sortedBy { it.timestamp }
    }

    fun between(preferences: Preferences, from: Long, to: Long): List<ProteinFatEntry> =
        history(preferences).filter { it.timestamp in from..to }

    fun unprocessedReady(preferences: Preferences, now: Long, minAgeMs: Long = 6 * 60 * 60 * 1000L): List<ProteinFatEntry> =
        history(preferences).filter { !it.processed && now - it.timestamp >= minAgeMs && now - it.timestamp <= 48 * 60 * 60 * 1000L }

    private fun toJson(entry: ProteinFatEntry) = JSONObject()
        .put("timestamp", entry.timestamp)
        .put("profile", entry.profileName)
        .put("protein", entry.proteinG)
        .put("fat", entry.fatG)
        .put("carbs", entry.carbsG)
        .put("delayedEq", entry.delayedCarbEquivalentG)
        .put("advisoryInsulin", entry.advisoryInsulinU)
        .put("dynamicIcr", entry.dynamicIcrGPerU)
        .put("profileIcr", entry.profileIcrGPerU)
        .put("profileIsf", entry.profileIsfMgdlPerU)
        .put("preBg", entry.preMealBgMgdl)
        .put("preTrend", entry.preMealTrendMgdlPer5m)
        .put("iob", entry.iobU)
        .put("autoIsfRatio", entry.autoIsfRatio)
        .put("tddRatio", entry.recentTddRatio)
        .put("neuralMultiplier", entry.neuralIcrMultiplier)
        .put("neuralConfidence", entry.neuralConfidence)
        .put("source", entry.source)
        .put("processed", entry.processed)
        .put("clean", entry.cleanEpisode)
        .put("lateError", entry.lateGlucoseErrorMgdl)
        .put("hypo", entry.hypoOccurred)

    private fun fromJson(obj: JSONObject) = ProteinFatEntry(
        timestamp = obj.getLong("timestamp"),
        profileName = obj.optString("profile", ""),
        proteinG = obj.optDouble("protein", 0.0),
        fatG = obj.optDouble("fat", 0.0),
        carbsG = obj.optDouble("carbs", 0.0),
        delayedCarbEquivalentG = obj.optDouble("delayedEq", 0.0),
        advisoryInsulinU = obj.optDouble("advisoryInsulin", 0.0),
        dynamicIcrGPerU = obj.optDouble("dynamicIcr", 0.0),
        profileIcrGPerU = obj.optDouble("profileIcr", 0.0),
        profileIsfMgdlPerU = obj.optDouble("profileIsf", 0.0),
        preMealBgMgdl = obj.optDouble("preBg", 0.0),
        preMealTrendMgdlPer5m = obj.optDouble("preTrend", 0.0),
        iobU = obj.optDouble("iob", 0.0),
        autoIsfRatio = obj.optDouble("autoIsfRatio", 1.0),
        recentTddRatio = obj.optDouble("tddRatio", 1.0),
        neuralIcrMultiplier = obj.optDouble("neuralMultiplier", 1.0),
        neuralConfidence = obj.optDouble("neuralConfidence", 0.0),
        source = obj.optString("source", "manual"),
        processed = obj.optBoolean("processed", false),
        cleanEpisode = if (obj.has("clean") && !obj.isNull("clean")) obj.optBoolean("clean") else null,
        lateGlucoseErrorMgdl = if (obj.has("lateError") && !obj.isNull("lateError")) obj.optDouble("lateError") else null,
        hypoOccurred = if (obj.has("hypo") && !obj.isNull("hypo")) obj.optBoolean("hypo") else null
    )
}
