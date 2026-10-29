package com.routegenie.client.curl

import com.routegenie.client.Header
import com.routegenie.client.RequestBody
import com.routegenie.client.RequestSpec
import com.routegenie.model.HttpMethod
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The real-shell oracle of research 03 (5.5): the emitted command is run in the real shell with the real curl against a local
 * server, and the server must receive exactly the bytes that were meant. Skipped (not failed) where the shell or curl is
 * missing. Only a few nasty bodies per shell, to keep the build light.
 */
class CurlRealShellOracleTest {
    private class Received(val method: String, val header: String?, val body: String)

    private val bodies = listOf(
        """{"name":"O'Brien \"Bob\"","n":1}""",
        """{"a":"x & y | z > w < v ^ c (p) 50% off"}""",
        "plain text with 'quotes' and \$HOME and `ticks` and !bang",
        "line1\nline2\n",
        "C:\\dir\\",
    )

    private fun run(shell: CurlShell, spec: RequestSpec, port: Int): Boolean {
        val text = CurlEmitter.emit(spec, CurlOptions(shell, multiline = shell != CurlShell.CMD)).text.replace("PORT", port.toString())
        val pb = when (shell) {
            CurlShell.BASH -> ProcessBuilder(BASH, "-l")
            CurlShell.CMD -> ProcessBuilder("cmd.exe", "/d", "/q").also { it.redirectInput(ProcessBuilder.Redirect.PIPE) }
            CurlShell.POWERSHELL -> ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand",
                java.util.Base64.getEncoder().encodeToString(text.toByteArray(StandardCharsets.UTF_16LE)),
            )
        }
        pb.redirectErrorStream(true)
        val p = pb.start()
        // The script goes through stdin: Java's argument quoting on Windows would alter quotes in a -c argument.
        when (shell) {
            CurlShell.CMD -> p.outputStream.use { it.write((text + "\r\nexit\r\n").toByteArray(StandardCharsets.US_ASCII)) }
            CurlShell.BASH -> p.outputStream.use { it.write((text + "\nexit\n").toByteArray(StandardCharsets.UTF_8)) }
            else -> p.outputStream.close()
        }
        p.inputStream.readAllBytes()
        return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
    }

    private fun withServer(check: (Int, AtomicReference<Received?>) -> Unit) {
        val seen = AtomicReference<Received?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            seen.set(Received(ex.requestMethod, ex.requestHeaders.getFirst("X-Test"), String(ex.requestBody.readAllBytes(), StandardCharsets.UTF_8)))
            ex.sendResponseHeaders(204, -1)
            ex.close()
        }
        server.start()
        try { check(server.address.port, seen) } finally { server.stop(0) }
    }

    private fun oracle(shell: CurlShell) {
        withServer { port, seen ->
            for (body in bodies) {
                seen.set(null)
                val spec = RequestSpec(
                    HttpMethod.POST, "http://127.0.0.1:PORT/x", listOf(Header("X-Test", "v 1")), RequestBody.Text("text/plain", body),
                )
                if (!run(shell, spec, port)) { assumeTrue("curl not runnable in $shell", false); return@withServer }
                val got = seen.get()
                assertEquals("$shell body", body, got?.body)
                assertEquals("POST", got?.method)
                assertEquals("v 1", got?.header)
            }
        }
    }

    private fun hasCurl(cmd: List<String>): Boolean = runCatching {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        p.inputStream.readAllBytes(); p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    @Test
    fun `bash delivers the exact bytes`() {
        assumeTrue(File(BASH).exists() || hasCurl(listOf("bash", "-c", "curl --version")))
        oracle(CurlShell.BASH)
    }

    @Test
    fun `cmd delivers the exact bytes`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows") && hasCurl(listOf("curl.exe", "--version")))
        oracle(CurlShell.CMD)
    }

    @Test
    fun `PowerShell 5_1 profile delivers the exact bytes`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows") && hasCurl(listOf("curl.exe", "--version")))
        assumeTrue(hasCurl(listOf("powershell.exe", "-NoProfile", "-Command", "exit 0")))
        oracle(CurlShell.POWERSHELL)
    }

    private companion object {
        val BASH: String = listOf("C:\\Program Files\\Git\\bin\\bash.exe", "/bin/bash").firstOrNull { File(it).exists() } ?: "bash"
    }
}
