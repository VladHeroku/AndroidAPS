package app.aaps.core.objects.meal

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OnlineMealLearnerTest {

    private fun features() = OnlineMealLearner.Features(
        hourOfDay = 13.0,
        carbsG = 60.0,
        proteinG = 25.0,
        fatG = 20.0,
        preMealBgMgdl = 110.0,
        preMealTrendMgdlPer5m = 0.0,
        iobU = 0.5,
        profileIcrGPerU = 10.0,
        autoIsfRatio = 1.0,
        recentTddRatio = 1.0
    )

    @Test
    fun startsNearNeutralAndNotReady() {
        val learner = OnlineMealLearner()
        val p = learner.predict(features())
        assertThat(p.icrMultiplier).isGreaterThan(0.85)
        assertThat(p.icrMultiplier).isLessThan(1.15)
        assertThat(p.readyForAdvisory).isFalse()
    }

    @Test
    fun repeatedPositiveNeedLearnsLowerIcr() {
        val learner = OnlineMealLearner()
        repeat(80) {
            learner.learn(
                features(),
                OnlineMealLearner.EpisodeOutcome(
                    normalizedInsulinNeedError = 0.8,
                    cleanEpisode = true,
                    hypoglycemiaOccurred = false
                )
            )
        }
        assertThat(learner.predict(features()).icrMultiplier).isLessThan(1.0)
    }

    @Test
    fun hypoBlocksLearningTowardMoreInsulin() {
        val learner = OnlineMealLearner()
        repeat(80) {
            learner.learn(
                features(),
                OnlineMealLearner.EpisodeOutcome(
                    normalizedInsulinNeedError = 1.0,
                    cleanEpisode = true,
                    hypoglycemiaOccurred = true
                )
            )
        }
        assertThat(learner.predict(features()).icrMultiplier).isAtLeast(0.97)
    }

    @Test
    fun dirtyEpisodesDoNotTrain() {
        val learner = OnlineMealLearner()
        val before = learner.predict(features())
        repeat(100) {
            learner.learn(
                features(),
                OnlineMealLearner.EpisodeOutcome(
                    normalizedInsulinNeedError = 1.0,
                    cleanEpisode = false,
                    hypoglycemiaOccurred = false
                )
            )
        }
        val after = learner.predict(features())
        assertThat(after.observations).isEqualTo(0)
        assertThat(after.icrMultiplier).isWithin(1e-12).of(before.icrMultiplier)
    }

    @Test
    fun stateRoundTripIsStable() {
        val a = OnlineMealLearner()
        repeat(25) {
            a.learn(
                features(),
                OnlineMealLearner.EpisodeOutcome(0.5, cleanEpisode = true, hypoglycemiaOccurred = false)
            )
        }
        val state = a.exportState()
        val b = OnlineMealLearner()
        b.importState(state)
        assertThat(b.predict(features()).icrMultiplier)
            .isWithin(1e-12)
            .of(a.predict(features()).icrMultiplier)
    }
}
