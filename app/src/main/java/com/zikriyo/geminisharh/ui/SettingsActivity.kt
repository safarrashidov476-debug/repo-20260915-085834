package com.zikriyo.geminisharh.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.zikriyo.geminisharh.R
import com.zikriyo.geminisharh.api.GeminiApiClient
import com.zikriyo.geminisharh.data.Prefs
import com.zikriyo.geminisharh.data.VoiceModels
import com.zikriyo.geminisharh.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    private var currentVoices = VoiceModels.MALE_VOICES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        prefs = Prefs(this)

        binding.etApiKey.setText(prefs.apiKey)
        binding.etAzureKey.setText(prefs.azureKey)
        binding.etAzureRegion.setText(prefs.azureRegion)
        binding.etDelay.setText(prefs.requestDelaySec.toString())

        if (prefs.voiceGender == "female") {
            binding.rbFemale.isChecked = true
            currentVoices = VoiceModels.FEMALE_VOICES
        } else {
            binding.rbMale.isChecked = true
            currentVoices = VoiceModels.MALE_VOICES
        }

        setupVoiceSpinner()
        setupModelSpinner()

        binding.rgGender.setOnCheckedChangeListener { _, checkedId ->
            currentVoices = if (checkedId == R.id.rbFemale) {
                VoiceModels.FEMALE_VOICES
            } else {
                VoiceModels.MALE_VOICES
            }
            setupVoiceSpinner()
        }

        binding.btnGetApiKey.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
        }

        binding.btnGetAzure.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://portal.azure.com/#create/Microsoft.CognitiveServicesSpeechServices")))
        }

        binding.btnTestApi.setOnClickListener {
            val key = binding.etApiKey.text?.toString()?.trim().orEmpty()
            if (key.isEmpty()) {
                Toast.makeText(this, R.string.no_api_key, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val client = GeminiApiClient(key)
                val (ok, msg) = client.testApiKey()
                Toast.makeText(
                    this@SettingsActivity,
                    if (ok) getString(R.string.api_ok) + " ($msg)"
                    else getString(R.string.api_fail) + ": $msg",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        binding.btnSave.setOnClickListener {
            prefs.apiKey = binding.etApiKey.text?.toString().orEmpty()
            prefs.azureKey = binding.etAzureKey.text?.toString().orEmpty()
            prefs.azureRegion = binding.etAzureRegion.text?.toString().orEmpty().ifBlank { "eastus" }
            prefs.requestDelaySec = binding.etDelay.text?.toString()?.toFloatOrNull() ?: 3f
            prefs.voiceGender = if (binding.rbFemale.isChecked) "female" else "male"

            val voicePos = binding.spinnerVoice.selectedItemPosition
            if (voicePos in currentVoices.indices) {
                prefs.selectedVoiceId = currentVoices[voicePos].id
            }

            val modelPos = binding.spinnerModel.selectedItemPosition
            if (modelPos in VoiceModels.VISION_MODELS.indices) {
                prefs.selectedVisionModel = VoiceModels.VISION_MODELS[modelPos].id
            }

            Toast.makeText(this, R.string.save, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun setupVoiceSpinner() {
        val names = currentVoices.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        binding.spinnerVoice.adapter = adapter

        val currentId = prefs.selectedVoiceId
        val idx = currentVoices.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        binding.spinnerVoice.setSelection(idx)
    }

    private fun setupModelSpinner() {
        val names = VoiceModels.VISION_MODELS.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        binding.spinnerModel.adapter = adapter

        val currentId = prefs.selectedVisionModel
        val idx = VoiceModels.VISION_MODELS.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        binding.spinnerModel.setSelection(idx)
    }
}
