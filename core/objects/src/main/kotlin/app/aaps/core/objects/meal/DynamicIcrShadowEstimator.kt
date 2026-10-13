package app.aaps.core.objects.meal

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Non-actuating Dynamic I:C estimator.
 *
 * IMPORTANT: this class is deliberately isolated from insulin delivery.
 * It produces shadow/advisory values only. The caller must not use [DynamicIcrShadowResult.dosingIcrGPerU]
 * to command insulin delivery without separate validation and an explicit safety review.
 *
 * Design goals:
 * 1. Keep dosing adaptation separate from COB/absorption modelling.
 * 2. Allow partial, bounded coupling to an AutoISF-like sensitivity signal without blindly mirroring
 *    every transient AutoISF factor into meal insulin.
 * 3. Preserve profile carb sensitivity factor (CSF = ISF / ICR) in the absorption shadow model when
 *    a rolling/average ISF is supplied.
 * 4. Suppress any more-aggressive I:C suggestion in a low/falling/exercise context.
 */
data class DynamicIcrShadowInput(
    val profileIcrGPerU: Double,
    val profileIsfMgdlPerU: Double,

    /** Current or preferably pre-meal/robust sensitivity estimate. */
    val sensitivityIsfMgdlPerU: Double? = null,

    /**
     * ISF used only for the absorption/COB shadow calculation.
     * Prefer a slow rolling value (for example a 24 h average), not an instantaneous postprandial value.
     */
    val absorptionIsfMgdlPerU: Double? = null,

    /**
     * Slowly learned meal-specific multiplier.
     * < 1.0 means stronger meal insulin (lower I:C); > 1.0 means weaker meal insulin.
     */
    val learnedMealMultiplier: Double = 1.0,
    val learningConfidence: Double = 0.0,

    /**
     * Fraction of the sensitivity change allowed to influence dosing I:C.
     * 0 = ignore sensitivity signal, 1 = full proportional coupling.
     * Shadow default is intentionally partial to reduce double-counting with AutoISF.
     */
    val sensitivityCoupling: Double = 0.35,
    /** Maximum absolute change around profile I:C, e.g. 0.15 = +/-15%. */
    val maxAdjustmentFraction: Double = 0.15,

    val currentBgMgdl: Double? = null,
    val minPredictedBgMgdl: Double? = null,
    val trendMgdlPer5m: Double? = null,
    val recentHypoglycemia: Boolean = false,
    val exerciseContext: Boolean = false,

    val safetyFloorMgdl: Double = 90.0,
    val fallingTrendLimitMgdlPer5m: Double = -2.0,

    val fatG: Double = 0.0,
    val proteinG: Double = 0.0
)

data class DynamicIcrShadowResult(
    val profileIcrGPerU: Double,
    val dosingIcrGPerU: Double,
    val absorptionIcrGPerU: Double,
    val sensitivityRatioRaw: Double,
    val sensitivityRatioUsed: Double,
    val dosingMultiplier: Double,
    val learnedMultiplierUsed: Double,
    val absorptionCsfMgdlPerG: Double,
    val delayedMealEnergyKcal: Double,
    val aggressivenessSuppressed: Boolean,
    val advisoryOnly: Boolean = true,
    val reasons: List<String>
)

object DynamicIcrShadowEstimator {

    private const val MIN_SENSITIVITY_RATIO = 0.75
    private const val MAX_SENSITIVITY_RATIO = 1.25
    private const val MIN_LEARNED_MULTIPLIER = 0.80
    private const val MAX_LEARNED_MULTIPLIER = 1.20
    private const val ABSOLUTE_MIN_DOSING_MULTIPLIER = 0.60
    private const val ABSOLUTE_MAX_DOSING_MULTIPLIER = 1.40
    private const val MIN_ABSORPTION_RATIO = 0.50
    private const val MAX_ABSORPTION_RATIO = 2.00

    fun estimate(input: DynamicIcrShadowInput): DynamicIcrShadowResult {
        require(input.profileIcrGPerU > 0.0) { "profileIcrGPerU must be > 0" }
        require(input.profileIsfMgdlPerU > 0.0) { "profileIsfMgdlPerU must be > 0" }

        val reasons = mutableListOf<String>()

        val rawSensitivityRatio =
            (input.sensitivityIsfMgdlPerU ?: input.profileIsfMgdlPerU) / input.profileIsfMgdlPerU
        val sensitivityRatio = rawSensitivityRatio.coerceIn(MIN_SENSITIVITY_RATIO, MAX_SENSITIVITY_RATIO)
        if (sensitivityRatio != rawSensitivityRatio) reasons += "sensitivity ratio clipped"

        val coupling = input.sensitivityCoupling.coerceIn(0.0, 1.0)
        val sensitivityMultiplier = sensitivityRatio.pow(coupling)

        val learnedRaw = input.learnedMealMultiplier.coerceIn(MIN_LEARNED_MULTIPLIER, MAX_LEARNED_MULTIPLIER)
        val confidence = input.learningConfidence.coerceIn(0.0, 1.0)
        // Log-domain blending makes +x% and -x% adaptations approximately symmetric.
        val learnedUsed = exp(ln(learnedRaw) * confidence)

        val maxAdjustment = input.maxAdjustmentFraction.coerceIn(0.02, 0.40)
        val minMultiplier = (1.0 - maxAdjustment).coerceAtLeast(ABSOLUTE_MIN_DOSING_MULTIPLIER)
        val maxMultiplier = (1.0 + maxAdjustment).coerceAtMost(ABSOLUTE_MAX_DOSING_MULTIPLIER)
        var dosingMultiplier =
            (sensitivityMultiplier * learnedUsed).coerceIn(minMultiplier, maxMultiplier)

        val lowOrFalling =
            input.recentHypoglycemia ||
                input.exerciseContext ||
                (input.currentBgMgdl?.let { it < input.safetyFloorMgdl } == true) ||
                (input.minPredictedBgMgdl?.let { it < input.safetyFloorMgdl } == true) ||
                (input.trendMgdlPer5m?.let { it < input.fallingTrendLimitMgdlPer5m } == true)

        var suppressed = false
        // A lower multiplier means lower I:C and therefore more meal insulin.
        // Shadow safety rule: never strengthen the meal recommendation in a low/falling/exercise context.
        if (lowOrFalling && dosingMultiplier < 1.0) {
            dosingMultiplier = 1.0
            suppressed = true
            reasons += "more-aggressive I:C suppressed by safety context"
        }

        val dosingIcr = input.profileIcrGPerU * dosingMultiplier

        // Keep the absorption model independent from transient dosing adaptation.
        // If a rolling ISF is provided, scale I:C proportionally so CSF = ISF / ICR stays equal
        // to the profile CSF. This directly prevents variable ISF alone from distorting carb decay.
        val absorptionIsf = input.absorptionIsfMgdlPerU ?: input.profileIsfMgdlPerU
        val absorptionRatio =
            (absorptionIsf / input.profileIsfMgdlPerU).coerceIn(MIN_ABSORPTION_RATIO, MAX_ABSORPTION_RATIO)
        val absorptionIcr = input.profileIcrGPerU * absorptionRatio
        val absorptionCsf = absorptionIsf / absorptionIcr

        val delayedMealEnergy = input.fatG.coerceAtLeast(0.0) * 9.0 + input.proteinG.coerceAtLeast(0.0) * 4.0
        if (delayedMealEnergy > 0.0) reasons += "fat/protein recorded as delayed-meal context only"

        if (input.sensitivityIsfMgdlPerU == null) reasons += "no dynamic sensitivity signal; profile I:C retained"
        if (confidence == 0.0) reasons += "learned meal factor inactive"

        return DynamicIcrShadowResult(
            profileIcrGPerU = input.profileIcrGPerU,
            dosingIcrGPerU = dosingIcr,
            absorptionIcrGPerU = absorptionIcr,
            sensitivityRatioRaw = rawSensitivityRatio,
            sensitivityRatioUsed = sensitivityRatio,
            dosingMultiplier = dosingMultiplier,
            learnedMultiplierUsed = learnedUsed,
            absorptionCsfMgdlPerG = absorptionCsf,
            delayedMealEnergyKcal = delayedMealEnergy,
            aggressivenessSuppressed = suppressed,
            reasons = reasons
        )
    }
}
