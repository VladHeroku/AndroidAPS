package app.aaps.core.objects.meal

import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence for the small online meal learner. State is kept per profile so profile-specific
 * physiology does not leak into another profile.
 */
object OnlineMealLearnerStore {

    fun load(
        preferences: Preferences,
        profileName: String,
        learningRate: Double,
        maxAdjustmentFraction: Double,
        minObservations: Int
    ): OnlineMealLearner {
        val learner = OnlineMealLearner(
            learningRate = learningRate,
            maxAdjustmentFraction = maxAdjustmentFraction,
            minObservations = minObservations
        )
        val root = parseObject(preferences.get(StringNonKey.AdaptiveMealNeuralState))
        val obj = root.optJSONObject(profileName) ?: return learner
        try {
            learner.importState(
                OnlineMealLearner.State(
                    w1 = jsonMatrix(obj.getJSONArray("w1")),
                    b1 = jsonVector(obj.getJSONArray("b1")),
                    w2 = jsonVector(obj.getJSONArray("w2")),
                    b2 = obj.optDouble("b2", 0.0),
                    observations = obj.optInt("observations", 0),
                    emaAbsError = obj.optDouble("emaAbsError", 1.0)
                )
            )
        } catch (_: Exception) {
            // Corrupt or old state: fall back to a neutral learner.
        }
        return learner
    }

    fun save(preferences: Preferences, profileName: String, learner: OnlineMealLearner) {
        val root = parseObject(preferences.get(StringNonKey.AdaptiveMealNeuralState))
        val state = learner.exportState()
        root.put(
            profileName,
            JSONObject()
                .put("w1", matrixJson(state.w1))
                .put("b1", vectorJson(state.b1))
                .put("w2", vectorJson(state.w2))
                .put("b2", state.b2)
                .put("observations", state.observations)
                .put("emaAbsError", state.emaAbsError)
        )
        preferences.put(StringNonKey.AdaptiveMealNeuralState, root.toString())
    }

    fun reset(preferences: Preferences, profileName: String) {
        val root = parseObject(preferences.get(StringNonKey.AdaptiveMealNeuralState))
        root.remove(profileName)
        preferences.put(StringNonKey.AdaptiveMealNeuralState, root.toString())
    }

    fun appendLog(preferences: Preferences, event: JSONObject) {
        val arr = try { JSONArray(preferences.get(StringNonKey.AdaptiveMealLearningLog)) } catch (_: Exception) { JSONArray() }
        arr.put(event)
        val trimmed = JSONArray()
        val start = (arr.length() - 300).coerceAtLeast(0)
        for (i in start until arr.length()) trimmed.put(arr.getJSONObject(i))
        preferences.put(StringNonKey.AdaptiveMealLearningLog, trimmed.toString())
    }

    private fun parseObject(raw: String) = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }

    private fun vectorJson(v: DoubleArray): JSONArray = JSONArray().also { a -> v.forEach { a.put(it) } }

    private fun matrixJson(m: Array<DoubleArray>): JSONArray =
        JSONArray().also { rows -> m.forEach { rows.put(vectorJson(it)) } }

    private fun jsonVector(a: JSONArray): DoubleArray =
        DoubleArray(a.length()) { i -> a.getDouble(i) }

    private fun jsonMatrix(a: JSONArray): Array<DoubleArray> =
        Array(a.length()) { i -> jsonVector(a.getJSONArray(i)) }
}
