package com.routegenie.clientide

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.routegenie.Branding
import com.routegenie.client.SecretLookup

/** Where secrets live. The IDE implementation is PasswordSafe; tests use a map. Blocking: never call on the UI thread. */
interface SecretStore {
    fun get(scope: String, key: String): String?
    fun set(scope: String, key: String, value: String?)

    /** The secrets of one scope (an environment) as the lookup the request preparer reads. */
    fun lookup(scope: String): SecretLookup = SecretLookup { get(scope, it) }
}

/** Secrets in the IDE's credential store (OS keychain or the KeePass file); nothing secret ever reaches an XML file. */
class PasswordSafeSecretStore(private val projectKey: String) : SecretStore {
    private fun attributes(scope: String, key: String) =
        CredentialAttributes(generateServiceName(Branding.NAME, "$projectKey/$scope/$key"))

    override fun get(scope: String, key: String): String? = PasswordSafe.instance.getPassword(attributes(scope, key))

    override fun set(scope: String, key: String, value: String?) {
        PasswordSafe.instance.setPassword(attributes(scope, key), value)
    }
}

/** The credential store scoped to one project, so the same environment name in two projects keeps separate secrets. */
fun passwordSafeFor(project: com.intellij.openapi.project.Project): SecretStore = PasswordSafeSecretStore(project.locationHash)
