package app.aaps.core.objects.meal

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Non-actuating Dynamic I:C estimator for AAPS 3.3.1.3 preview.
 *
 * AutoISF is context, not a second copy of the controller. The coupling is intentionally partial and
 * bounded to reduce double-counting of postprandial insulin resistance.
 */
data class DynamicIcrPreviewInput(
    val profileIcrGPerU: Double,
    val profileIsfMgdlPerU: Double,
    val dynamicIsfMgdlPerU: Double? = null,
    val learnedMealMultiplier: Double = 1.0,
    val learningConfidence: Double = 0.0,
    val sensitivityCoupling: Double = 0.35,
    val currentBgMgdl: Double? = null,
    val trendMgdlPer5m: Double? = null,
    val recentHypoglycemia: Boolean = false,
    val exerciseContext: Boolean = false,
    val safetyFloorMgdl: Double = 90.0
)

data class DynamicIcrPreviewResult(
    val profileIcrGPerU: Double,
    val dynamicIcrGPerU: Double,
    val sensitivityRatio: Double,
    val multiplier: Double,
    val confidence: Double,
    val aggressivenessSuppressed: Boolean,
    val advisoryOnly: Boolean = true
)

object DynamicIcrPreviewEstimator {

    fun estimate(input: DynamicIcrPreviewInput): DynamicIcrPreviewResult {
        require(input.profileIcrGPerU > 0.0)
        require(input.profileIsfMgdlPerU > 0.0)

        val rawRatio = (input.dynamicIsfMgdlPerU ?: input.profileIsfMgdlPerU) / input.profileIsfMgdlPerU
        val ratio = rawRatio.coerceIn(0.75, 1.25)
        val coupling = input.sensitivityCoupling.coerceIn(0.0, 1.0)
        val sensitivityMultiplier = ratio.pow(coupling)

        val learned = input.learnedMealMultiplier.coerceIn(0.85, 1.15)
        val confidence = input.learningConfidence.coerceIn(0.0, 1.0)
        val learnedUsed = exp(ln(learned) * confidence)

        var multiplier = (sensitivityMultiplier * learnedUsed).coerceIn(0.80, 1.20)

        val lowOrFalling =
            input.recentHypoglycemia ||
                input.exerciseContext ||
                (input.currentBgMgdl?.let { it < input.safetyFloorMgdl } == true) ||
                (input.trendMgdlPer5m?.let { it < -2.0 } == true)

        var suppressed = false
        if (lowOrFalling && multiplier < 1.0) {
            multiplier = 1.0
            suppressed = true
        }

        return DynamicIcrPreviewResult(
            profileIcrGPerU = input.profileIcrGPerU,
            dynamicIcrGPerU = input.profileIcrGPerU * multiplier,
            sensitivityRatio = ratio,
            multiplier = multiplier,
            confidence = confidence,
            aggressivenessSuppressed = suppressed
        )
    }
}
