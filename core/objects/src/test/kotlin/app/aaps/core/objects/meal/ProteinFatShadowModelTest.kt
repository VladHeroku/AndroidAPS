package app.aaps.core.objects.meal

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ProteinFatShadowModelTest {

    @Test
    fun zeroCoefficientsProduceNoAdvisory() {
        val r = ProteinFatShadowModel.estimate(
            proteinG = 40.0,
            fatG = 30.0,
            config = ProteinFatProfileConfig(),
            dynamicIcrGPerU = 10.0
        )
        assertThat(r.delayedCarbEquivalentG).isWithin(1e-9).of(0.0)
        assertThat(r.advisoryInsulinU).isWithin(1e-9).of(0.0)
    }

    @Test
    fun fixedCoefficientsMapToDelayedEquivalent() {
        val r = ProteinFatShadowModel.estimate(
            proteinG = 30.0,
            fatG = 20.0,
            config = ProteinFatProfileConfig(
                proteinCarbEqPerGram = 0.25,
                fatCarbEqPerGram = 0.10
            ),
            dynamicIcrGPerU = 10.0
        )
        assertThat(r.delayedCarbEquivalentG).isWithin(1e-9).of(9.5)
        assertThat(r.advisoryInsulinU).isWithin(1e-9).of(0.95)
        assertThat(r.kineticCurve.sumOf { it.carbEquivalentG }).isWithin(1e-9).of(9.5)
    }

    @Test
    fun learnerIsSlowAndBounded() {
        var config = ProteinFatProfileConfig(
            proteinCarbEqPerGram = 0.25,
            fatCarbEqPerGram = 0.10,
            dynamicLearnerEnabled = true
        )
        repeat(100) {
            config = ProteinFatAdaptiveLearner.update(
                config,
                ProteinFatAdaptiveLearner.Outcome(
                    proteinG = 30.0,
                    fatG = 20.0,
                    lateGlucoseErrorMgdl = 200.0,
                    cleanObservation = true
                )
            )
        }
        assertThat(config.learnedProteinMultiplier).isAtMost(1.30)
        assertThat(config.learnedFatMultiplier).isAtMost(1.30)
        assertThat(config.learningConfidence).isWithin(1e-9).of(1.0)
    }

    @Test
    fun dirtyObservationDoesNotLearn() {
        val before = ProteinFatProfileConfig(dynamicLearnerEnabled = true)
        val after = ProteinFatAdaptiveLearner.update(
            before,
            ProteinFatAdaptiveLearner.Outcome(
                proteinG = 30.0,
                fatG = 20.0,
                lateGlucoseErrorMgdl = 100.0,
                cleanObservation = false
            )
        )
        assertThat(after).isEqualTo(before)
    }
}
