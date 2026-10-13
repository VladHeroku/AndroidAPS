package app.aaps.plugins.main.general.overview

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.meal.DynamicIcrShadowEstimator
import app.aaps.core.objects.meal.DynamicIcrShadowInput
import app.aaps.core.objects.meal.OnlineMealLearner
import app.aaps.core.objects.meal.OnlineMealLearnerStore
import app.aaps.core.objects.meal.ProteinFatEntryStore
import app.aaps.core.objects.meal.ProteinFatProfileStore
import app.aaps.core.objects.meal.ProteinFatShadowModel
import org.json.JSONObject
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Non-actuating minute-by-minute adaptive controller simulation.
 *
 * It deliberately cannot send pump commands. It writes the controller state to Preferences so the
 * complete adaptive decision path can be inspected while AAPS is connected to Virtual Pump.
 */
@Singleton
class AdaptiveMealVirtualLoopSimulator @Inject constructor(
    private val preferences: Preferences,
    private val profileFunction: ProfileFunction,
    private val activePlugin: ActivePlugin,
    private val iobCobCalculator: IobCobCalculator,
    private val dateUtil: DateUtil
) {

    @Synchronized
    fun run(): JSONObject? {
        if (!preferences.get(BooleanKey.AdaptiveMealVirtualClosedLoop)) return null
        if (activePlugin.activePump.pumpDescription.pumpType != PumpType.GENERIC_AAPS) return null

        val profile = profileFunction.getProfile() ?: return null
        val bg = iobCobCalculator.ads.actualBg()?.value ?: return null
        val now = dateUtil.now()
        val profileIsf = profile.getProfileIsfMgdl()
        val dynamicIsf = if (activePlugin.activeAPS.supportsDynamicIsf()) {
            activePlugin.activeAPS.getIsfMgdl(profile, "AdaptiveMealVirtualLoop") ?: profileIsf
        } else profileIsf

        val recent = ProteinFatEntryStore.between(preferences, now - SIX_HOURS, now)
        val latest = recent.lastOrNull()
        val iob = iobCobCalculator.calculateIobFromBolus().iob +
            iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().basaliob

        var neuralMultiplier = 1.0
        var neuralConfidence = 0.0
        var observations = 0
        if (preferences.get(BooleanKey.AdaptiveMealNeuralLearningEnabled)) {
            val learner = OnlineMealLearnerStore.load(
                preferences,
                profileFunction.getProfileName(),
                preferences.get(DoubleKey.AdaptiveMealNeuralLearningRate),
                preferences.get(DoubleKey.AdaptiveMealIcrMaxAdjustment),
                preferences.get(IntKey.AdaptiveMealMinObservations)
            )
            val calendar = Calendar.getInstance().apply { timeInMillis = now }
            val prediction = learner.predict(
                OnlineMealLearner.Features(
                    hourOfDay = calendar.get(Calendar.HOUR_OF_DAY) + calendar.get(Calendar.MINUTE) / 60.0,
                    carbsG = latest?.carbsG ?: 0.0,
                    proteinG = latest?.proteinG ?: 0.0,
                    fatG = latest?.fatG ?: 0.0,
                    preMealBgMgdl = latest?.preMealBgMgdl?.takeIf { it > 0.0 } ?: bg,
                    preMealTrendMgdlPer5m = latest?.preMealTrendMgdlPer5m ?: 0.0,
                    iobU = iob,
                    profileIcrGPerU = profile.getIc(),
                    autoIsfRatio = (dynamicIsf / profileIsf).coerceIn(0.4, 2.5),
                    recentTddRatio = latest?.recentTddRatio ?: 1.0
                )
            )
            neuralMultiplier = prediction.icrMultiplier
            neuralConfidence = prediction.confidence
            observations = prediction.observations
        }

        val dynamicIcr = DynamicIcrShadowEstimator.estimate(
            DynamicIcrShadowInput(
                profileIcrGPerU = profile.getIc(),
                profileIsfMgdlPerU = profileIsf,
                sensitivityIsfMgdlPerU = dynamicIsf,
                learnedMealMultiplier = neuralMultiplier,
                learningConfidence = neuralConfidence,
                sensitivityCoupling = if (preferences.get(BooleanKey.AdaptiveMealDynamicIcrEnabled))
                    preferences.get(DoubleKey.AdaptiveMealIcrSensitivityCoupling) else 0.0,
                maxAdjustmentFraction = preferences.get(DoubleKey.AdaptiveMealIcrMaxAdjustment),
                currentBgMgdl = bg
            )
        )

        var releasedEquivalent = 0.0
        recent.forEach { entry ->
            val ageMinutes = ((now - entry.timestamp) / MINUTE).toInt()
            if (ageMinutes in 0..360) {
                val result = ProteinFatShadowModel.estimate(
                    entry.proteinG,
                    entry.fatG,
                    ProteinFatProfileStore.load(preferences, entry.profileName),
                    dynamicIcr.dosingIcrGPerU
                )
                val bin = (ageMinutes / 30).coerceIn(0, result.kineticCurve.lastIndex)
                releasedEquivalent += result.kineticCurve[bin].carbEquivalentG
            }
        }

        val delayedMealEquivalentU = releasedEquivalent / dynamicIcr.dosingIcrGPerU
        val correctionEquivalentU = ((bg - 110.0) / dynamicIsf).coerceIn(-5.0, 5.0)
        val netEquivalentU = (delayedMealEquivalentU + correctionEquivalentU - max(0.0, iob)).coerceIn(0.0, 10.0)

        val state = JSONObject()
            .put("timestamp", now)
            .put("mode", "SIMULATION_ONLY")
            .put("pump", "VirtualPump")
            .put("bg", bg)
            .put("profileIcr", profile.getIc())
            .put("dynamicIcr", dynamicIcr.dosingIcrGPerU)
            .put("profileIsf", profileIsf)
            .put("dynamicIsf", dynamicIsf)
            .put("neuralMultiplier", neuralMultiplier)
            .put("neuralConfidence", neuralConfidence)
            .put("observations", observations)
            .put("activeProteinFatEquivalentG", releasedEquivalent)
            .put("delayedMealEquivalentU", delayedMealEquivalentU)
            .put("correctionEquivalentU", correctionEquivalentU)
            .put("iobU", iob)
            .put("simulatedNetInsulinU", netEquivalentU)
            .put("actuated", false)

        preferences.put(StringNonKey.AdaptiveMealVirtualState, state.toString())
        return state
    }

    companion object {
        private const val MINUTE = 60L * 1000L
        private const val SIX_HOURS = 6L * 60L * 60L * 1000L
    }
}
