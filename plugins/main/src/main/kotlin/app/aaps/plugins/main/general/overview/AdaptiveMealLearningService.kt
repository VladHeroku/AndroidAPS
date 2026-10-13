package app.aaps.plugins.main.general.overview

import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.meal.OnlineMealLearner
import app.aaps.core.objects.meal.OnlineMealLearnerStore
import app.aaps.core.objects.meal.ProteinFatAdaptiveLearner
import app.aaps.core.objects.meal.ProteinFatEntryStore
import app.aaps.core.objects.meal.ProteinFatProfileStore
import org.json.JSONObject
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class AdaptiveMealLearningService @Inject constructor(
    private val preferences: Preferences,
    private val persistenceLayer: PersistenceLayer,
    private val dateUtil: DateUtil
) {

    private var lastProcessTimestamp = 0L

    @Synchronized
    fun processReadyEpisodes(force: Boolean = false): Int {
        val now = dateUtil.now()
        if (!force && now - lastProcessTimestamp < FIVE_MINUTES) return 0
        lastProcessTimestamp = now

        val neuralEnabled = preferences.get(BooleanKey.AdaptiveMealNeuralLearningEnabled)
        val macroEnabled = preferences.get(BooleanKey.AdaptiveMealDynamicMacroEnabled)
        if (!neuralEnabled && !macroEnabled) return 0

        val allEntries = ProteinFatEntryStore.history(preferences)
        val ready = ProteinFatEntryStore.unprocessedReady(preferences, now)
        var processed = 0

        ready.forEach { entry ->
            val end = entry.timestamp + SIX_HOURS
            val readings = persistenceLayer.getBgReadingsDataFromTimeToTime(entry.timestamp, end, true)
            val lateReadings = readings.filter { it.timestamp >= entry.timestamp + THREE_HOURS }

            // Keep incomplete episodes pending; they may become usable on the next pass.
            if (readings.size < 45 || lateReadings.size < 18) return@forEach

            val coverageOk = readings.zipWithNext().all { (a, b) -> b.timestamp - a.timestamp <= TWENTY_MINUTES }
            val extraCarbs = persistenceLayer
                .getCarbsFromTimeToTimeExpanded(entry.timestamp + TWENTY_MINUTES, end, true)
                .any { it.amount > 0.0 }
            val exercise = persistenceLayer.getTherapyEventDataFromToTime(entry.timestamp, end)
                .blockingGet()
                .any { it.type == TE.Type.EXERCISE }
            val overlappingMeal = allEntries.any {
                it.timestamp != entry.timestamp &&
                    it.timestamp > entry.timestamp + TWENTY_MINUTES &&
                    it.timestamp < entry.timestamp + FOUR_HOURS
            }

            val hypo = readings.any { it.value < 70.0 }
            val clean = coverageOk && !extraCarbs && !exercise && !overlappingMeal

            val target = if (entry.preMealBgMgdl > 0.0) entry.preMealBgMgdl.coerceIn(90.0, 130.0) else 110.0
            val lateAverage = lateReadings.map { it.value }.average()
            val lateError = lateAverage - target

            var neuralMultiplier = entry.neuralIcrMultiplier
            var neuralConfidence = entry.neuralConfidence

            if (neuralEnabled) {
                val learner = OnlineMealLearnerStore.load(
                    preferences = preferences,
                    profileName = entry.profileName,
                    learningRate = preferences.get(DoubleKey.AdaptiveMealNeuralLearningRate),
                    maxAdjustmentFraction = preferences.get(DoubleKey.AdaptiveMealIcrMaxAdjustment),
                    minObservations = preferences.get(IntKey.AdaptiveMealMinObservations)
                )
                val prediction = learner.learn(
                    features = features(entry),
                    outcome = OnlineMealLearner.EpisodeOutcome(
                        normalizedInsulinNeedError = (lateError / 80.0).coerceIn(-1.0, 1.0),
                        cleanEpisode = clean,
                        hypoglycemiaOccurred = hypo
                    )
                )
                OnlineMealLearnerStore.save(preferences, entry.profileName, learner)
                neuralMultiplier = prediction.icrMultiplier
                neuralConfidence = prediction.confidence
            }

            if (macroEnabled) {
                val config = ProteinFatProfileStore.load(preferences, entry.profileName)
                if (config.dynamicLearnerEnabled) {
                    ProteinFatProfileStore.save(
                        preferences,
                        entry.profileName,
                        ProteinFatAdaptiveLearner.update(
                            config,
                            ProteinFatAdaptiveLearner.Outcome(
                                proteinG = entry.proteinG,
                                fatG = entry.fatG,
                                lateGlucoseErrorMgdl = lateError,
                                cleanObservation = clean,
                                hypoglycemiaOccurred = hypo,
                                stepLimit = preferences.get(DoubleKey.AdaptiveMealMacroLearningStep),
                                maxAdjustment = preferences.get(DoubleKey.AdaptiveMealMacroMaxAdjustment)
                            )
                        )
                    )
                }
            }

            val updated = entry.copy(
                processed = true,
                cleanEpisode = clean,
                lateGlucoseErrorMgdl = lateError,
                hypoOccurred = hypo,
                neuralIcrMultiplier = neuralMultiplier,
                neuralConfidence = neuralConfidence
            )
            ProteinFatEntryStore.update(preferences, updated)
            OnlineMealLearnerStore.appendLog(
                preferences,
                JSONObject()
                    .put("timestamp", now)
                    .put("mealTimestamp", entry.timestamp)
                    .put("profile", entry.profileName)
                    .put("clean", clean)
                    .put("hypo", hypo)
                    .put("coverageOk", coverageOk)
                    .put("extraCarbs", extraCarbs)
                    .put("exercise", exercise)
                    .put("overlap", overlappingMeal)
                    .put("lateAverage", lateAverage)
                    .put("target", target)
                    .put("lateError", lateError)
                    .put("neuralMultiplier", neuralMultiplier)
                    .put("neuralConfidence", neuralConfidence)
            )
            processed++
        }

        return processed
    }

    private fun features(entry: app.aaps.core.objects.meal.ProteinFatEntry): OnlineMealLearner.Features {
        val calendar = Calendar.getInstance().apply { timeInMillis = entry.timestamp }
        return OnlineMealLearner.Features(
            hourOfDay = calendar.get(Calendar.HOUR_OF_DAY) + calendar.get(Calendar.MINUTE) / 60.0,
            carbsG = entry.carbsG,
            proteinG = entry.proteinG,
            fatG = entry.fatG,
            preMealBgMgdl = if (entry.preMealBgMgdl > 0.0) entry.preMealBgMgdl else 110.0,
            preMealTrendMgdlPer5m = entry.preMealTrendMgdlPer5m,
            iobU = entry.iobU,
            profileIcrGPerU = if (entry.profileIcrGPerU > 0.0) entry.profileIcrGPerU else 10.0,
            autoIsfRatio = entry.autoIsfRatio.coerceIn(0.4, 2.5),
            recentTddRatio = entry.recentTddRatio.coerceIn(0.5, 2.0)
        )
    }

    companion object {
        private const val FIVE_MINUTES = 5L * 60L * 1000L
        private const val TWENTY_MINUTES = 20L * 60L * 1000L
        private const val THREE_HOURS = 3L * 60L * 60L * 1000L
        private const val FOUR_HOURS = 4L * 60L * 60L * 1000L
        private const val SIX_HOURS = 6L * 60L * 60L * 1000L
    }
}
