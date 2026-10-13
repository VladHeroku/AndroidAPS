package app.aaps.core.objects.meal

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DynamicIcrShadowEstimatorTest {

    @Test
    fun neutralSensitivityKeepsProfileIcr() {
        val result = DynamicIcrShadowEstimator.estimate(
            DynamicIcrShadowInput(
                profileIcrGPerU = 10.0,
                profileIsfMgdlPerU = 50.0,
                sensitivityIsfMgdlPerU = 50.0,
                absorptionIsfMgdlPerU = 50.0
            )
        )

        assertThat(result.dosingIcrGPerU).isWithin(1e-9).of(10.0)
        assertThat(result.absorptionIcrGPerU).isWithin(1e-9).of(10.0)
    }

    @Test
    fun resistanceSignalMakesShadowIcrMoreAggressiveButOnlyPartially() {
        val result = DynamicIcrShadowEstimator.estimate(
            DynamicIcrShadowInput(
                profileIcrGPerU = 10.0,
                profileIsfMgdlPerU = 50.0,
                sensitivityIsfMgdlPerU = 40.0,
                sensitivityCoupling = 0.35
            )
        )

        assertThat(result.dosingIcrGPerU).isLessThan(10.0)
        // Full coupling would be 8.0 g/U; shadow default must remain less aggressive than that.
        assertThat(result.dosingIcrGPerU).isGreaterThan(8.0)
    }

    @Test
    fun sensitiveSignalMakesShadowIcrWeaker() {
        val result = DynamicIcrShadowEstimator.estimate(
            DynamicIcrShadowInput(
                profileIcrGPerU = 10.0,
                profileIsfMgdlPerU = 50.0,
                sensitivityIsfMgdlPerU = 60.0
            )
        )

        assertThat(result.dosingIcrGPerU).isGreaterThan(10.0)
    }

    @Test
    fun lowPredictionSuppressesMoreAggressiveSuggestion() {
        val result = DynamicIcrShadowEstimator.estimate(
            DynamicIcrShadowInput(
                profileIcrGPerU = 10.0,
                profileIsfMgdlPerU = 50.0,
                sensitivityIsfMgdlPerU = 35.0,
                minPredictedBgMgdl = 78.0
            )
        )

        assertThat(result.dosingIcrGPerU).isWithin(1e-9).of(10.0)
        assertThat(result.aggressivenessSuppressed).isTrue()
    }

    @Test
    fun zeroConfidenceIgnoresLearnedMealMultiplier() {
        val result = DynamicIcrShadowEstimator.estimate(
            DynamicIcrShadowInput(
                profileIcrGPerU = 10.0,
                profileIsfMgdlPerU = 50.0,
                learnedMealMultiplier = 0.80,
                learningConfidence = 0.0
            )
        )

        assertThat(result.dosingIcrGPerU).isWithin(1e-9).of(10.0)
    }

    @Test
    fun absorptionShadowPreservesProfileCsf() {
        val profileIcr = 10.0
        val profileIsf = 50.0
        val rollingIsf = 35.0

        val result = DynamicIcrShadowEstimator.estimate(
            DynamicIcrShadowInput(
                profileIcrGPerU = profileIcr,
                profileIsfMgdlPerU = profileIsf,
                absorptionIsfMgdlPerU = rollingIsf
            )
        )

        val profileCsf = profileIsf / profileIcr
        val shadowCsf = rollingIsf / result.absorptionIcrGPerU
        assertThat(shadowCsf).isWithin(1e-9).of(profileCsf)
    }

    @Test
    fun fatAndProteinAreContextNotImmediateInsulin() {
        val result = DynamicIcrShadowEstimator.estimate(
            DynamicIcrShadowInput(
                profileIcrGPerU = 10.0,
                profileIsfMgdlPerU = 50.0,
                fatG = 20.0,
                proteinG = 30.0
            )
        )

        assertThat(result.delayedMealEnergyKcal).isWithin(1e-9).of(300.0)
        assertThat(result.dosingIcrGPerU).isWithin(1e-9).of(10.0)
    }
}
