package com.github.nearkim.aicodewalkthrough.settings

import com.github.nearkim.aicodewalkthrough.model.AiProvider
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.Service

@Service(Service.Level.APP)
class ProviderSecretsService {

    fun getApiKey(provider: AiProvider): String? {
        require(provider.requiresApiKey) { "Provider ${provider.displayName} does not use an API key" }
        return PasswordSafe.instance.getPassword(attributesFor(provider))?.takeIf { it.isNotBlank() }
    }

    fun setApiKey(provider: AiProvider, apiKey: String?) {
        require(provider.requiresApiKey) { "Provider ${provider.displayName} does not use an API key" }
        val normalized = apiKey?.trim().orEmpty()
        if (normalized.isBlank()) {
            PasswordSafe.instance.set(attributesFor(provider), null)
            return
        }

        PasswordSafe.instance.set(
            attributesFor(provider),
            Credentials(provider.id, normalized),
        )
    }

    fun hasApiKey(provider: AiProvider): Boolean = !getApiKey(provider).isNullOrBlank()

    private fun attributesFor(provider: AiProvider): CredentialAttributes {
        val serviceName = generateServiceName(SERVICE_NAME, provider.id)
        return CredentialAttributes(serviceName, provider.id)
    }

    private companion object {
        const val SERVICE_NAME = "AI Code Walkthrough"
    }
}
