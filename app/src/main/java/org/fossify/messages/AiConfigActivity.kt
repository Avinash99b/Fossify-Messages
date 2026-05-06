package org.fossify.messages

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.ViewGroup
import android.widget.Toast
import android.widget.TextView
import android.widget.ScrollView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setPadding
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.viewBinding
import org.fossify.messages.databinding.ActivityAiConfigBinding
import org.fossify.messages.helpers.AiConfigProvider
import org.fossify.messages.helpers.AiDecisionLogger
import org.fossify.messages.helpers.AiRequestHelper

class AiConfigActivity : AppCompatActivity() {

    val binding by viewBinding(ActivityAiConfigBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        configure()
    }

    private fun configure(){
        val aiConfigProvider = AiConfigProvider(this)

        // Load existing values
        binding.apiKeyInput.setText(aiConfigProvider.apiKey ?: "")
        binding.modelNameInput.setText(aiConfigProvider.model ?: "")
        binding.promptInput.setText(aiConfigProvider.customPrompt ?: "")

        // Save button click listener
        binding.saveBtn.setOnClickListener {
            saveConfiguration(aiConfigProvider)
        }

        binding.aiLogsBtn.setOnClickListener {
            showAiLogsDialog()
        }

        // Reset prompt button click listener
        binding.resetPromptBtn.setOnClickListener {
            resetPrompt()
        }

        // Clear data button click listener
        binding.clearDataBtn.setOnClickListener {
            clearAllData(aiConfigProvider)
        }
    }

    private fun saveConfiguration(aiConfigProvider: AiConfigProvider) {
        val apiKey = binding.apiKeyInput.text?.toString()?.trim().orEmpty()
        val modelName = binding.modelNameInput.text?.toString()?.trim().orEmpty()
        val customPrompt = binding.promptInput.text?.toString()?.trim()

        // Validate inputs
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Please enter a valid API key", Toast.LENGTH_SHORT).show()
            return
        }

        if (modelName.isEmpty()) {
            Toast.makeText(this, "Please enter a valid Model Name", Toast.LENGTH_SHORT).show()
            return
        }

        // Save configuration
        aiConfigProvider.apiKey = apiKey
        aiConfigProvider.model = modelName
        aiConfigProvider.customPrompt = customPrompt

        Toast.makeText(this, "Configuration saved successfully", Toast.LENGTH_SHORT).show()
    }

    private fun resetPrompt() {
        val defaultPrompt = AiRequestHelper.defaultPrompt()
        binding.promptInput.setText(defaultPrompt)
        AiConfigProvider(this).customPrompt = defaultPrompt

        Toast.makeText(this, "Prompt reset to default", Toast.LENGTH_SHORT).show()
    }

    private fun clearAllData(aiConfigProvider: AiConfigProvider) {
        aiConfigProvider.clear()

        // Clear UI fields
        binding.apiKeyInput.setText("")
        binding.modelNameInput.setText("")
        binding.promptInput.setText(aiConfigProvider.customPrompt)

        Toast.makeText(this, "All configuration data cleared", Toast.LENGTH_SHORT).show()
    }

    private fun showAiLogsDialog() {
        val logs = AiDecisionLogger.getLogs(this)

        val textView = TextView(this).apply {
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod.getInstance()
            textSize = 13f
            setPadding((4 * resources.displayMetrics.density).toInt())
            text = if (logs.isEmpty()) {
                getString(R.string.ai_logs_empty)
            } else {
                logs.joinToString("\n\n────────────────────\n\n") { AiDecisionLogger.formatLogEntry(it) }
            }
        }

        val scrollView = ScrollView(this).apply {
            setPadding((16 * resources.displayMetrics.density).toInt())
            addView(
                textView,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }

        getAlertDialogBuilder()
            .setTitle(R.string.ai_logs_title)
            .setView(scrollView)
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .show()
    }
}
