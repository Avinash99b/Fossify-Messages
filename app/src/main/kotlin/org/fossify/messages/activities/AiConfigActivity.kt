package org.fossify.messages.activities

import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ScrollView
import android.widget.TextView
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityAiConfigBinding
import org.fossify.messages.helpers.AiConfigProvider
import org.fossify.messages.helpers.AiDecisionLogger
import org.fossify.messages.helpers.AiLogEntry
import org.fossify.messages.helpers.AiRequestHelper
import java.net.URI
import java.text.DateFormat
import java.util.Date

@Suppress("TooManyFunctions")
class AiConfigActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityAiConfigBinding::inflate)
    private val config by lazy { AiConfigProvider(this) }

    private val providerValues = listOf(
        AiConfigProvider.PROVIDER_GEMINI,
        AiConfigProvider.PROVIDER_OPENAI,
        AiConfigProvider.PROVIDER_OTHER,
        AiConfigProvider.PROVIDER_DECIDE,
    )

    private val providerLabels by lazy {
        listOf(
            getString(R.string.ai_provider_gemini),
            getString(R.string.ai_provider_openai),
            getString(R.string.ai_provider_other),
            getString(R.string.ai_provider_decide),
        )
    }

    private var selectedProvider = AiConfigProvider.PROVIDER_GEMINI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupProviderDropdown()
        setupButtons()
        loadConfiguration()
    }

    private fun setupProviderDropdown() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, providerLabels)
        binding.providerInput.setAdapter(adapter)
        binding.providerInput.setOnItemClickListener { _, _, position, _ ->
            val provider = providerValues.getOrElse(position) { AiConfigProvider.PROVIDER_GEMINI }
            selectProvider(provider)
        }
    }

    private fun setupButtons() {
        binding.saveBtn.setOnClickListener { saveConfiguration() }
        binding.resetPromptBtn.setOnClickListener { resetPrompt() }
        binding.aiLogsBtn.setOnClickListener { showLogs() }
        binding.clearDataBtn.setOnClickListener { clearConfiguration() }
    }

    private fun loadConfiguration() {
        selectedProvider = config.provider
        if (selectedProvider !in providerValues) {
            selectedProvider = AiConfigProvider.PROVIDER_GEMINI
        }
        binding.providerInput.setText(providerLabel(selectedProvider), false)
        binding.customEndpointInput.setText(config.customEndpoint)
        binding.modelNameInput.setText(config.model)
        binding.apiKeyInput.setText(config.apiKey)
        binding.promptInput.setText(config.customPrompt)
        updateProviderVisibility()
    }

    private fun selectProvider(provider: String) {
        selectedProvider = provider
        updateProviderVisibility()
    }

    private fun updateProviderVisibility() {
        val isDecide = selectedProvider == AiConfigProvider.PROVIDER_DECIDE
        val usesCustomEndpoint = selectedProvider == AiConfigProvider.PROVIDER_OTHER || isDecide
        binding.customEndpointHolder.beVisibleIf(usesCustomEndpoint)

        binding.customEndpointLayout.helperText = getString(
            if (isDecide) {
                R.string.ai_config_decide_endpoint_helper
            } else {
                R.string.ai_config_custom_endpoint_helper
            }
        )
        binding.modelNameLayout.helperText = if (isDecide) {
            getString(R.string.ai_config_decide_model_helper)
        } else {
            null
        }
        binding.apiKeyLayout.helperText = if (isDecide) {
            getString(R.string.ai_config_api_key_optional)
        } else {
            null
        }
    }

    private fun saveConfiguration() {
        val endpoint = binding.customEndpointInput.text?.toString()?.trim().orEmpty()
        val model = binding.modelNameInput.text?.toString()?.trim().orEmpty()
        val apiKey = binding.apiKeyInput.text?.toString()?.trim().orEmpty()
        val prompt = binding.promptInput.text?.toString()?.trim().orEmpty()

        val isDecide = selectedProvider == AiConfigProvider.PROVIDER_DECIDE
        if (!isDecide && model.isEmpty()) {
            toast(R.string.ai_error_model_required)
            return
        }
        if (!isDecide && apiKey.isEmpty()) {
            toast(R.string.ai_error_api_key_required)
            return
        }
        val endpointRequired = selectedProvider == AiConfigProvider.PROVIDER_OTHER ||
            (isDecide && endpoint.isNotEmpty())
        if (endpointRequired && !isValidHttpsEndpoint(endpoint)) {
            toast(R.string.ai_error_endpoint_invalid)
            return
        }

        config.provider = selectedProvider
        config.model = model
        config.apiKey = apiKey
        config.customEndpoint = endpoint
        config.customPrompt = prompt

        val savedMessage = if (config.isConfigured()) {
            R.string.ai_config_saved
        } else {
            R.string.ai_config_incomplete
        }
        toast(savedMessage)
    }

    private fun resetPrompt() {
        val defaultPrompt = AiRequestHelper.defaultPrompt()
        binding.promptInput.setText(defaultPrompt)
        config.customPrompt = defaultPrompt
        toast(R.string.ai_prompt_reset)
    }

    private fun clearConfiguration() {
        config.clear()
        selectedProvider = AiConfigProvider.PROVIDER_GEMINI
        binding.providerInput.setText(providerLabel(selectedProvider), false)
        binding.customEndpointInput.setText("")
        binding.modelNameInput.setText("")
        binding.apiKeyInput.setText("")
        binding.promptInput.setText(AiRequestHelper.defaultPrompt())
        updateProviderVisibility()
        toast(R.string.ai_config_cleared)
    }

    private fun showLogs() {
        val textView = TextView(this).apply {
            setText(formatLogs(AiDecisionLogger.getLogs(this@AiConfigActivity)))
            setTextIsSelectable(true)
            val padding = resources.getDimensionPixelSize(
                org.fossify.commons.R.dimen.normal_margin
            )
            setPadding(padding, padding, padding, padding)
        }
        val scrollView = ScrollView(this).apply {
            addView(textView)
        }
        val logHeight = (resources.displayMetrics.heightPixels * LOG_DIALOG_HEIGHT_FRACTION).toInt()
        scrollView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            logHeight
        )

        getAlertDialogBuilder()
            .setTitle(R.string.ai_logs_title)
            .setView(scrollView)
            .setPositiveButton(R.string.ai_close, null)
            .setNeutralButton(R.string.ai_logs_clear) { _, _ ->
                AiDecisionLogger.clearLogs(this)
                toast(R.string.ai_logs_cleared)
            }
            .show()
    }

    private fun formatLogs(entries: List<AiLogEntry>): String {
        if (entries.isEmpty()) {
            return getString(R.string.ai_logs_empty)
        }
        return entries.joinToString("\n\n") { formatLogEntry(it) }
    }

    private fun formatLogEntry(entry: AiLogEntry): String {
        val unknown = getString(R.string.ai_log_unknown)
        val none = getString(R.string.ai_log_none)
        val timestamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
            .format(Date(entry.timestamp))
        val notify = getString(
            if (entry.notify) R.string.ai_log_notify_true else R.string.ai_log_notify_false
        )

        return listOf(
            getString(R.string.ai_log_timestamp, timestamp),
            getString(R.string.ai_log_sender, entry.senderName.ifBlank { unknown }),
            getString(R.string.ai_log_status, entry.status.ifBlank { unknown }),
            getString(R.string.ai_log_category, entry.category.ifBlank { none }),
            getString(R.string.ai_log_notify, notify),
            getString(R.string.ai_log_reasoning, entry.reasoning.ifBlank { none }),
            getString(R.string.ai_log_preview, entry.messagePreview.ifBlank { none }),
        ).joinToString("\n")
    }

    private fun providerLabel(provider: String): String {
        val index = providerValues.indexOf(provider)
        return providerLabels.getOrElse(index) { providerLabels.first() }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun isValidHttpsEndpoint(raw: String): Boolean {
        return try {
            val uri = URI(raw)
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
        } catch (e: Exception) {
            false
        }
    }

    private companion object {
        const val LOG_DIALOG_HEIGHT_FRACTION = 0.6f
    }
}
