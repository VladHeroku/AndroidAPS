package app.aaps.ui.dialogs

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.objects.meal.MealMacroEntry
import app.aaps.core.objects.meal.MealMacroPreviewStore
import app.aaps.core.objects.meal.MealMacroShadowModel
import app.aaps.ui.R
import app.aaps.ui.databinding.DialogProteinFatBinding
import java.text.DecimalFormat
import javax.inject.Inject

class ProteinFatDialog : DialogFragmentWithDate() {

    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var sp: SP

    private var _binding: DialogProteinFatBinding? = null
    private val binding get() = _binding!!

    private val watcher = object : TextWatcher {
        override fun afterTextChanged(s: Editable) = updatePreview()
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        onCreateViewGeneral()
        _binding = DialogProteinFatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.protein.setParams(
            savedInstanceState?.getDouble("protein") ?: 0.0,
            0.0, 300.0, 1.0, DecimalFormat("0"), false, binding.okcancel.ok, watcher
        )
        binding.fat.setParams(
            savedInstanceState?.getDouble("fat") ?: 0.0,
            0.0, 300.0, 1.0, DecimalFormat("0"), false, binding.okcancel.ok, watcher
        )
        updatePreview()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putDouble("protein", binding.protein.value)
        outState.putDouble("fat", binding.fat.value)
    }

    private fun updatePreview() {
        val profile = profileFunction.getProfile() ?: return
        val profileName = profileFunction.getProfileName()
        val cfg = MealMacroPreviewStore.loadProfile(sp, profileName)
        val result = MealMacroShadowModel.estimate(binding.protein.value, binding.fat.value, cfg, profile.getIc())
        binding.preview.text = rh.gs(
            R.string.protein_fat_preview_value,
            result.delayedCarbEquivalentG,
            result.advisoryInsulinU,
            result.icrGPerU
        )
    }

    override fun submit(): Boolean {
        val protein = binding.protein.value
        val fat = binding.fat.value
        if (protein <= 0.0 && fat <= 0.0) return true
        MealMacroPreviewStore.saveEntry(
            sp,
            MealMacroEntry(
                timestamp = dateUtil.now(),
                profileName = profileFunction.getProfileName(),
                carbsG = 0.0,
                proteinG = protein,
                fatG = fat,
                source = "overview"
            )
        )
        return true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
