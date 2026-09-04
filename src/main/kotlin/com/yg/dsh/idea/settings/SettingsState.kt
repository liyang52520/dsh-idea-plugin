package com.yg.dsh.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/** Supported API protocols for custom providers. */
val SUPPORTED_PROTOCOLS = listOf("openai-completions", "openai-responses", "anthropic-messages")

@State(name = "DshSettings", storages = [Storage("dsh-settings.xml")])
class SettingsState : PersistentStateComponent<SettingsState> {

    var providers: MutableList<ProviderConfig> = mutableListOf()
    var nodePath: String? = null
    var dshPath: String? = null
    var dshArgs: String = ""

    /**
     * Maximum number of DSH runtimes (one Node.js process + embedded browser
     * per project) allowed to run concurrently. Clamped to [MIN_MAX_INSTANCES]
     * … [MAX_MAX_INSTANCES] at use sites.
     */
    var maxInstances: Int = DEFAULT_MAX_INSTANCES

    override fun getState(): SettingsState = this

    override fun loadState(state: SettingsState) {
        XmlSerializerUtil.copyBean(state, this)
        // Migrate old single-model provider configs to the new multi-model format.
        for (p in providers) {
            p.migrateFromLegacy()
        }
    }

    companion object {
        /** Default concurrency cap when the user has not configured one. */
        const val DEFAULT_MAX_INSTANCES = 3

        /** Hard bounds for the Advanced-settings spinner. */
        const val MIN_MAX_INSTANCES = 1
        const val MAX_MAX_INSTANCES = 10

        fun getInstance(): SettingsState =
            ApplicationManager.getApplication().getService(SettingsState::class.java)
    }
}

/** A single model entry within a custom provider. */
data class ModelConfig(
    var id: String = "",
    var name: String = "",
)

/** Custom provider configuration. */
data class ProviderConfig(
    /** Lowercase identifier, used as the provider route key (e.g. "acme-gateway"). */
    var id: String = "",
    /** Human-readable display name shown in selectors. */
    var displayName: String = "",
    /** API wire protocol (e.g. "openai-completions"). */
    var apiProtocol: String = "openai-completions",
    /** Base URL of the provider's API endpoint. */
    var baseUrl: String = "",
    /** API key for this provider. */
    var apiKey: String = "",
    /** Models available from this provider. */
    var models: MutableList<ModelConfig> = mutableListOf(),

    // -- legacy fields retained for XML deserialization of v0.1.x configs --

    /** @deprecated Replaced by [id]. Kept for backward compat. */
    @Suppress("DEPRECATION")
    @Deprecated("Replaced by id")
    var name: String = "",
    /** @deprecated Replaced by [models]. Kept for backward compat. */
    @Suppress("DEPRECATION")
    @Deprecated("Replaced by models")
    var model: String = "",
) {
    /**
     * Migrate this config from the legacy single-model format to the new
     * multi-model format. Idempotent: does nothing when [id] is already set.
     */
    fun migrateFromLegacy() {
        @Suppress("DEPRECATION")
        if (id.isBlank() && name.isNotBlank()) {
            id = name
            displayName = name
            name = ""
        }
        @Suppress("DEPRECATION")
        if (models.isEmpty() && model.isNotBlank()) {
            models.add(ModelConfig(id = model, name = model))
            model = ""
        }
    }

    /** The credential env-var name for this provider (e.g. "ACME_GATEWAY_API_KEY"). */
    val credentialEnvName: String
        get() = "${id.uppercase()}_API_KEY"
}