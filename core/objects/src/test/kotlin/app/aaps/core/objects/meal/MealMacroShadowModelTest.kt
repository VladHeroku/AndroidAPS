package app.aaps.core.objects.meal

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MealMacroShadowModelTest {

    @Test
    fun zeroCoefficientsRemainNeutral() {
        val r = MealMacroShadowModel.estimate(30.0, 20.0, MealMacroProfileConfig(), 10.0)
        assertThat(r.delayedCarbEquivalentG).isWithin(1e-9).of(0.0)
        assertThat(r.advisoryInsulinU).isWithin(1e-9).of(0.0)
    }

    @Test
    fun fixedCoefficientsUseDelayedCurve() {
        val r = MealMacroShadowModel.estimate(
            30.0, 20.0,
            MealMacroProfileConfig(proteinCarbEqPerGram = 0.25, fatCarbEqPerGram = 0.10),
            10.0
        )
        assertThat(r.delayedCarbEquivalentG).isWithin(1e-9).of(9.5)
        assertThat(r.advisoryInsulinU).isWithin(1e-9).of(0.95)
        assertThat(r.kineticCurve.sumOf { it.carbEquivalentG }).isWithin(1e-9).of(9.5)
    }

    @Test
    fun macroLearnerWillNotBecomeMoreAggressiveAfterHypo() {
        val c = MealMacroProfileConfig(
            proteinCarbEqPerGram = 0.25,
            fatCarbEqPerGram = 0.10,
            dynamicMacroLearningEnabled = true
        )
        val out = MealMacroAdaptiveLearner.update(
            c,
            MealMacroAdaptiveLearner.Outcome(
                proteinG = 30.0,
                fatG = 20.0,
                lateGlucoseErrorMgdl = 150.0,
                cleanEpisode = true,
                hypoglycemiaOccurred = true
            )
        )
        assertThat(out.learnedProteinMultiplier).isWithin(1e-9).of(1.0)
        assertThat(out.learnedFatMultiplier).isWithin(1e-9).of(1.0)
    }
}
