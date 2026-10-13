package app.aaps.ui.dialogs

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.objects.meal.ProteinFatEntry
import app.aaps.core.objects.meal.ProteinFatEntryStore
import app.aaps.core.objects.meal.ProteinFatProfileStore
import app.aaps.core.objects.meal.ProteinFatShadowModel
import app.aaps.ui.R
import app.aaps.ui.databinding.DialogProteinFatBinding
import java.text.DecimalFormat
import javax.inject.Inject

class ProteinFatDialog : DialogFragmentWithDate() {

    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction

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
        binding.protein.setParams(savedInstanceState?.getDouble("protein") ?: 0.0, 0.0, 300.0, 1.0, DecimalFormat("0"), false, binding.okcancel.ok, watcher)
        binding.fat.setParams(savedInstanceState?.getDouble("fat") ?: 0.0, 0.0, 300.0, 1.0, DecimalFormat("0"), false, binding.okcancel.ok, watcher)
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
        val cfg = ProteinFatProfileStore.load(preferences, profileName)
        val result = ProteinFatShadowModel.estimate(binding.protein.value, binding.fat.value, cfg, profile.getIc())
        binding.preview.text = rh.gs(
            R.string.protein_fat_preview_value,
            result.delayedCarbEquivalentG,
            result.advisoryInsulinU,
            result.dynamicIcrGPerU
        )
    }

    override fun submit(): Boolean {
        val protein = binding.protein.value
        val fat = binding.fat.value
        if (protein <= 0.0 && fat <= 0.0) return true
        val profile = profileFunction.getProfile() ?: return true
        val profileName = profileFunction.getProfileName()
        val result = ProteinFatShadowModel.estimate(
            protein,
            fat,
            ProteinFatProfileStore.load(preferences, profileName),
            profile.getIc()
        )
        val entry = ProteinFatEntry(
            timestamp = dateUtil.now(),
            profileName = profileName,
            proteinG = protein,
            fatG = fat,
            delayedCarbEquivalentG = result.delayedCarbEquivalentG,
            advisoryInsulinU = result.advisoryInsulinU,
            dynamicIcrGPerU = result.dynamicIcrGPerU,
            profileIcrGPerU = profile.getIc(),
            profileIsfMgdlPerU = profile.getProfileIsfMgdl(),
            source = "protein-fat-dialog"
        )
        ProteinFatEntryStore.setPending(preferences, entry)
        ProteinFatEntryStore.appendHistory(preferences, entry)
        return true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}