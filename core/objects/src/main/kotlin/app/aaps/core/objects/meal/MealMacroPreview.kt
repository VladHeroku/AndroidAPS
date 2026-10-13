package app.aaps.core.objects.meal

import app.aaps.core.interfaces.sharedPreferences.SP
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Non-actuating protein/fat model for the 3.3.1.3 preview build.
 *
 * Base coefficients are user-defined grams of delayed carbohydrate-equivalent per gram of
 * protein/fat. Optional dynamic multipliers are bounded and confidence weighted.
 */
data class MealMacroProfileConfig(
    val proteinCarbEqPerGram: Double = 0.0,
    val fatCarbEqPerGram: Double = 0.0,
    val dynamicMacroLearningEnabled: Boolean = false,
    val learnedProteinMultiplier: Double = 1.0,
    val learnedFatMultiplier: Double = 1.0,
    val learningConfidence: Double = 0.0
)

data class MealMacroKineticPoint(
    val minutesFromMeal: Int,
    val carbEquivalentG: Double
)

data class MealMacroShadowResult(
    val proteinG: Double,
    val fatG: Double,
    val effectiveProteinCoefficient: Double,
    val effectiveFatCoefficient: Double,
    val delayedCarbEquivalentG: Double,
    val advisoryInsulinU: Double,
    val icrGPerU: Double,
    val kineticCurve: List<MealMacroKineticPoint>,
    val dynamicLearningApplied: Boolean,
    val advisoryOnly: Boolean = true
)

object MealMacroShadowModel {

    // 30-min bins from 0 to 360 min. Kernels each sum to 1.
    private val proteinKernel = doubleArrayOf(
        0.00, 0.00, 0.00, 0.05, 0.10, 0.16, 0.20, 0.18, 0.13, 0.09, 0.06, 0.03, 0.00
    )
    private val fatKernel = doubleArrayOf(
        0.00, 0.00, 0.00, 0.00, 0.04, 0.08, 0.13, 0.17, 0.19, 0.17, 0.12, 0.07, 0.03
    )

    fun estimate(
        proteinG: Double,
        fatG: Double,
        config: MealMacroProfileConfig,
        icrGPerU: Double
    ): MealMacroShadowResult {
        require(icrGPerU > 0.0)

        val confidence = config.learningConfidence.coerceIn(0.0, 1.0)
        val pLearned = if (config.dynamicMacroLearningEnabled)
            blend(config.learnedProteinMultiplier, confidence) else 1.0
        val fLearned = if (config.dynamicMacroLearningEnabled)
            blend(config.learnedFatMultiplier, confidence) else 1.0

        val pCoeff = max(0.0, config.proteinCarbEqPerGram) * pLearned
        val fCoeff = max(0.0, config.fatCarbEqPerGram) * fLearned
        val pEq = max(0.0, proteinG) * pCoeff
        val fEq = max(0.0, fatG) * fCoeff
        val totalEq = pEq + fEq

        val curve = proteinKernel.indices.map { i ->
            MealMacroKineticPoint(
                minutesFromMeal = i * 30,
                carbEquivalentG = pEq * proteinKernel[i] + fEq * fatKernel[i]
            )
        }

        return MealMacroShadowResult(
            proteinG = max(0.0, proteinG),
            fatG = max(0.0, fatG),
            effectiveProteinCoefficient = pCoeff,
            effectiveFatCoefficient = fCoeff,
            delayedCarbEquivalentG = totalEq,
            advisoryInsulinU = totalEq / icrGPerU,
            icrGPerU = icrGPerU,
            kineticCurve = curve,
            dynamicLearningApplied = config.dynamicMacroLearningEnabled && confidence > 0.0
        )
    }

    private fun blend(raw: Double, confidence: Double): Double {
        val bounded = raw.coerceIn(0.70, 1.30)
        return exp(ln(bounded) * confidence)
    }
}

object MealMacroAdaptiveLearner {

    data class Outcome(
        val proteinG: Double,
        val fatG: Double,
        val lateGlucoseErrorMgdl: Double,
        val cleanEpisode: Boolean,
        val hypoglycemiaOccurred: Boolean
    )

    fun update(config: MealMacroProfileConfig, outcome: Outcome): MealMacroProfileConfig {
        if (!config.dynamicMacroLearningEnabled || !outcome.cleanEpisode) return config
        val macroTotal = outcome.proteinG.coerceAtLeast(0.0) + outcome.fatG.coerceAtLeast(0.0)
        if (macroTotal < 10.0) return config

        var step = (outcome.lateGlucoseErrorMgdl / 100.0).coerceIn(-0.03, 0.03)
        if (outcome.hypoglycemiaOccurred && step > 0.0) step = 0.0

        val proteinShare = outcome.proteinG.coerceAtLeast(0.0) / macroTotal
        val fatShare = 1.0 - proteinShare

        return config.copy(
            learnedProteinMultiplier =
                (config.learnedProteinMultiplier * (1.0 + step * proteinShare)).coerceIn(0.70, 1.30),
            learnedFatMultiplier =
                (config.learnedFatMultiplier * (1.0 + step * fatShare)).coerceIn(0.70, 1.30),
            learningConfidence = (config.learningConfidence + 0.04).coerceAtMost(1.0)
        )
    }
}

data class MealMacroEntry(
    val timestamp: Long,
    val profileName: String,
    val carbsG: Double,
    val proteinG: Double,
    val fatG: Double,
    val source: String
)

object MealMacroPreviewStore {

    private const val PROFILE_KEY = "meal_macro_preview_profiles_v1"
    private const val HISTORY_KEY = "meal_macro_preview_history_v1"
    private const val PENDING_KEY = "meal_macro_preview_pending_v1"
    private const val MAX_HISTORY = 250

    fun loadProfile(sp: SP, profileName: String): MealMacroProfileConfig {
        val root = parseObject(sp.getString(PROFILE_KEY, "{}"))
        val o = root.optJSONObject(profileName) ?: return MealMacroProfileConfig()
        return MealMacroProfileConfig(
            proteinCarbEqPerGram = o.optDouble("pCoeff", 0.0),
            fatCarbEqPerGram = o.optDouble("fCoeff", 0.0),
            dynamicMacroLearningEnabled = o.optBoolean("dynamic", false),
            learnedProteinMultiplier = o.optDouble("pLearned", 1.0),
            learnedFatMultiplier = o.optDouble("fLearned", 1.0),
            learningConfidence = o.optDouble("confidence", 0.0)
        )
    }

    fun saveProfile(sp: SP, profileName: String, config: MealMacroProfileConfig) {
        val root = parseObject(sp.getString(PROFILE_KEY, "{}"))
        root.put(
            profileName,
            JSONObject()
                .put("pCoeff", config.proteinCarbEqPerGram)
                .put("fCoeff", config.fatCarbEqPerGram)
                .put("dynamic", config.dynamicMacroLearningEnabled)
                .put("pLearned", config.learnedProteinMultiplier)
                .put("fLearned", config.learnedFatMultiplier)
                .put("confidence", config.learningConfidence)
        )
        sp.putString(PROFILE_KEY, root.toString())
    }

    fun saveEntry(sp: SP, entry: MealMacroEntry) {
        sp.putString(PENDING_KEY, entryToJson(entry).toString())
        val history = parseArray(sp.getString(HISTORY_KEY, "[]"))
        history.put(entryToJson(entry))
        val trimmed = JSONArray()
        val start = max(0, history.length() - MAX_HISTORY)
        for (i in start until history.length()) trimmed.put(history.getJSONObject(i))
        sp.putString(HISTORY_KEY, trimmed.toString())
    }

    fun pending(sp: SP, now: Long, maxAgeMs: Long = 2L * 60L * 60L * 1000L): MealMacroEntry? {
        val raw = sp.getString(PENDING_KEY, "")
        if (raw.isBlank()) return null
        return try {
            val e = entryFromJson(JSONObject(raw))
            if (now - e.timestamp in 0..maxAgeMs) e else null
        } catch (_: Exception) {
            null
        }
    }

    private fun parseObject(raw: String) = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
    private fun parseArray(raw: String) = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }

    private fun entryToJson(e: MealMacroEntry) = JSONObject()
        .put("timestamp", e.timestamp)
        .put("profile", e.profileName)
        .put("carbs", e.carbsG)
        .put("protein", e.proteinG)
        .put("fat", e.fatG)
        .put("source", e.source)

    private fun entryFromJson(o: JSONObject) = MealMacroEntry(
        timestamp = o.getLong("timestamp"),
        profileName = o.optString("profile", ""),
        carbsG = o.optDouble("carbs", 0.0),
        proteinG = o.optDouble("protein", 0.0),
        fatG = o.optDouble("fat", 0.0),
        source = o.optString("source", "")
    )
}
