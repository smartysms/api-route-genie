package com.routegenie.clientide

import com.intellij.openapi.application.PathManager
import com.intellij.util.net.ssl.CertificateManager
import com.routegenie.Branding
import com.routegenie.client.JdkHttpEngine
import com.routegenie.client.SpillDirectory
import com.routegenie.client.TlsMode
import java.net.Authenticator
import java.nio.file.Path

/**
 * Builds the engine with what only the IDE knows: the trust store of the certificate manager, the proxy login the IDE
 * installed as the JVM default authenticator (the proxy selector is the JVM default the IDE also installed, which the JDK
 * client reads by itself), and the IDE's temp folder for spill files.
 */
object IdeEngineFactory {
    private const val SPILL_FOLDER = "routegenie"

    fun spillDirectory(): SpillDirectory = SpillDirectory(Path.of(PathManager.getTempPath(), SPILL_FOLDER))

    fun create(spill: SpillDirectory = spillDirectory()): JdkHttpEngine = JdkHttpEngine(
        sslContextFor = { mode -> if (mode == TlsMode.IDE_TRUST) CertificateManager.getInstance().sslContext else null },
        authenticator = Authenticator.getDefault(),
        spill = spill,
        userAgent = Branding.NAME.replace(' ', '-'),
    )
}
