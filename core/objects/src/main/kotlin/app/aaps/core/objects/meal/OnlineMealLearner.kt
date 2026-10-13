package app.aaps.core.objects.meal

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Small online neural learner for meal-response personalization.
 *
 * NON-ACTUATING BY DESIGN:
 * this class never sends insulin and does not replace AAPS I:C.
 * It learns a bounded shadow multiplier that can later be displayed/replayed.
 *
 * The network is intentionally tiny and deterministic:
 *  - 10 normalized inputs
 *  - 8 tanh hidden units
 *  - 1 bounded output (log I:C multiplier)
 *  - online SGD after a completed, "clean" meal episode
 *
 * A tiny model is preferable here to a large neural network because personal datasets are small,
 * non-stationary and safety-critical.
 */
class OnlineMealLearner(
    private val hiddenSize: Int = 8,
    private val learningRate: Double = 0.0025,
    private val l2: Double = 1e-4
) {

    data class Features(
        val hourOfDay: Double,
        val carbsG: Double,
        val proteinG: Double,
        val fatG: Double,
        val preMealBgMgdl: Double,
        val preMealTrendMgdlPer5m: Double,
        val iobU: Double,
        val profileIcrGPerU: Double,
        val autoIsfRatio: Double,
        val recentTddRatio: Double
    )

    data class EpisodeOutcome(
        /**
         * Positive means more meal insulin would probably have been useful;
         * negative means less would probably have been useful.
         *
         * This should be produced from a post-meal replay/outcome layer, not from a single CGM point.
         */
        val normalizedInsulinNeedError: Double,

        /** Exclude episodes confounded by rescue carbs, exercise, sensor gaps, site failure, etc. */
        val cleanEpisode: Boolean,

        /** Any clinically relevant low in the episode blocks learning toward more insulin. */
        val hypoglycemiaOccurred: Boolean
    )

    data class Prediction(
        val icrMultiplier: Double,
        val confidence: Double,
        val observations: Int,
        val readyForAdvisory: Boolean,
        val rawNetworkOutput: Double
    )

    data class State(
        val w1: Array<DoubleArray>,
        val b1: DoubleArray,
        val w2: DoubleArray,
        val b2: Double,
        val observations: Int,
        val emaAbsError: Double
    )

    private var w1 = Array(hiddenSize) { h ->
        DoubleArray(INPUTS) { i -> deterministicInitialWeight(h, i) }
    }
    private var b1 = DoubleArray(hiddenSize)
    private var w2 = DoubleArray(hiddenSize) { i -> deterministicInitialWeight(hiddenSize + 1, i) * 0.35 }
    private var b2 = 0.0

    private var observations = 0
    private var emaAbsError = 1.0

    fun predict(features: Features): Prediction {
        val x = normalize(features)
        val hidden = DoubleArray(hiddenSize) { h ->
            tanh(dot(w1[h], x) + b1[h])
        }
        val raw = dot(w2, hidden) + b2

        // Bound learned I:C change to roughly +/-15% in shadow mode.
        // Positive raw output = more insulin need -> lower I:C.
        val boundedLogMultiplier = (-0.14 * tanh(raw))
        val multiplier = exp(boundedLogMultiplier)

        val dataConfidence = (observations / 40.0).coerceIn(0.0, 1.0)
        val errorConfidence = (1.0 - emaAbsError.coerceIn(0.0, 1.0))
        val confidence = (dataConfidence * (0.35 + 0.65 * errorConfidence)).coerceIn(0.0, 1.0)

        return Prediction(
            icrMultiplier = multiplier,
            confidence = confidence,
            observations = observations,
            readyForAdvisory = observations >= MIN_OBSERVATIONS && confidence >= 0.45,
            rawNetworkOutput = raw
        )
    }

    fun learn(features: Features, outcome: EpisodeOutcome): Prediction {
        if (!outcome.cleanEpisode) return predict(features)

        val x = normalize(features)
        val hidden = DoubleArray(hiddenSize) { h -> tanh(dot(w1[h], x) + b1[h]) }
        val raw = dot(w2, hidden) + b2

        // Target is intentionally clipped. One unusual meal must not swing the model.
        var target = outcome.normalizedInsulinNeedError.coerceIn(-1.0, 1.0)

        // Never learn "more insulin" from an episode that contained hypoglycemia.
        if (outcome.hypoglycemiaOccurred && target > 0.0) target = 0.0

        val error = raw - target
        val gradOut = error.coerceIn(-1.5, 1.5)

        val oldW2 = w2.copyOf()

        for (h in 0 until hiddenSize) {
            w2[h] -= learningRate * (gradOut * hidden[h] + l2 * w2[h])
        }
        b2 -= learningRate * gradOut

        for (h in 0 until hiddenSize) {
            val hiddenGrad = gradOut * oldW2[h] * (1.0 - hidden[h] * hidden[h])
            for (i in 0 until INPUTS) {
                w1[h][i] -= learningRate * (hiddenGrad * x[i] + l2 * w1[h][i])
            }
            b1[h] -= learningRate * hiddenGrad
        }

        observations++
        emaAbsError = if (observations == 1) abs(error) else 0.95 * emaAbsError + 0.05 * abs(error)

        return predict(features)
    }

    fun exportState(): State = State(
        w1 = Array(hiddenSize) { w1[it].copyOf() },
        b1 = b1.copyOf(),
        w2 = w2.copyOf(),
        b2 = b2,
        observations = observations,
        emaAbsError = emaAbsError
    )

    fun importState(state: State) {
        require(state.w1.size == hiddenSize)
        require(state.w2.size == hiddenSize)
        require(state.w1.all { it.size == INPUTS })
        w1 = Array(hiddenSize) { state.w1[it].copyOf() }
        b1 = state.b1.copyOf()
        w2 = state.w2.copyOf()
        b2 = state.b2
        observations = max(0, state.observations)
        emaAbsError = state.emaAbsError.coerceIn(0.0, 5.0)
    }

    private fun normalize(f: Features): DoubleArray {
        val angle = 2.0 * Math.PI * ((f.hourOfDay % 24.0 + 24.0) % 24.0) / 24.0
        return doubleArrayOf(
            kotlin.math.sin(angle),
            kotlin.math.cos(angle),
            squash(f.carbsG / 80.0),
            squash(f.proteinG / 50.0),
            squash(f.fatG / 40.0),
            squash((f.preMealBgMgdl - 110.0) / 80.0),
            squash(f.preMealTrendMgdlPer5m / 10.0),
            squash(f.iobU / 5.0),
            squash((f.autoIsfRatio - 1.0) / 0.5),
            squash((f.recentTddRatio - 1.0) / 0.5)
        )
    }

    private fun squash(v: Double): Double = tanh(v)

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var out = 0.0
        for (i in a.indices) out += a[i] * b[i]
        return out
    }

    private fun deterministicInitialWeight(a: Int, b: Int): Double {
        val seed = ((a + 1) * 37 + (b + 1) * 17) % 101
        return (seed / 100.0 - 0.5) * 0.08
    }

    companion object {
        private const val INPUTS = 10
        private const val MIN_OBSERVATIONS = 20
    }
}
