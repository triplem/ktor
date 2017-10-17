package io.ktor.testing

import io.ktor.application.Application
import io.ktor.application.install
import io.ktor.client.http2.Http2Client
import io.ktor.client.jvm.DefaultHttpClient
import io.ktor.client.jvm.HttpResponse
import io.ktor.client.jvm.RequestBuilder
import io.ktor.features.CallLogging
import io.ktor.host.*
import io.ktor.routing.Routing
import io.ktor.util.generateCertificate
import kotlinx.coroutines.experimental.*
import org.junit.*
import org.junit.rules.TestName
import org.junit.rules.Timeout
import org.junit.runners.model.MultipleFailureException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.net.*
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import kotlin.test.fail


abstract class HostTestBase<THost : ApplicationHost>(val applicationHostFactory: ApplicationHostFactory<THost>) {
    protected val isUnderDebugger = java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments.orEmpty().any { "-agentlib:jdwp" in it }
    protected var port = findFreePort()
    protected var sslPort = findFreePort()
    protected var server: THost? = null
    protected val exceptions = ArrayList<Throwable>()
    protected var enableHttp2: Boolean = System.getProperty("enable.http2") == "true"
    protected var enableSsl: Boolean = System.getProperty("enable.ssl") != "false"

    private val allConnections = CopyOnWriteArrayList<HttpURLConnection>()

    val testLog: Logger = LoggerFactory.getLogger("HostTestBase")

    @Target(AnnotationTarget.FUNCTION)
    @Retention
    protected annotation class Http2Only

    @Target(AnnotationTarget.FUNCTION)
    @Retention
    protected annotation class NoHttp2

    @get:Rule
    val test = TestName()

    @get:Rule
    val timeout = PublishedTimeout(if (isUnderDebugger) 1000000L else (System.getProperty("host.test.timeout.seconds")?.toLong() ?: 120L))

    @Before
    fun setUpBase() {
        val method = this.javaClass.getMethod(test.methodName) ?: fail("Method ${test.methodName} not found")

        if (method.isAnnotationPresent(Http2Only::class.java)) {
            Assume.assumeTrue("http2 is not enabled", enableHttp2)
        }
        if (method.isAnnotationPresent(NoHttp2::class.java)) {
            enableHttp2 = false
        }

        if (enableHttp2) {
            Class.forName("sun.security.ssl.ALPNExtension", true, null)
        }

        testLog.trace("Starting server on port $port (SSL $sslPort)")
        exceptions.clear()
    }

    @After
    fun tearDownBase() {
        allConnections.forEach { it.disconnect() }
        testLog.trace("Disposing server on port $port (SSL $sslPort)")
        (server as? ApplicationHost)?.stop(1000, 5000, TimeUnit.MILLISECONDS)
        if (exceptions.isNotEmpty()) {
            fail("Server exceptions logged, consult log output for more information")
        }
    }

    protected open fun createServer(log: Logger?, module: Application.() -> Unit): THost {
        val _port = this.port
        val environment = applicationHostEnvironment {
            val delegate = LoggerFactory.getLogger("ktor.test")
            this.log = log ?: object : Logger by delegate {
                override fun error(msg: String?, t: Throwable?) {
                    t?.let { exceptions.add(it) }
                    delegate.error(msg, t)
                }
            }

            connector { port = _port }
            if (enableSsl) {
                sslConnector(keyStore, "mykey", { "changeit".toCharArray() }, { "changeit".toCharArray() }) {
                    this.port = sslPort
                    this.keyStorePath = keyStoreFile.absoluteFile
                }
            }

            module(module)
        }
        return embeddedServer(applicationHostFactory, environment)
    }

    protected open fun features(application: Application, routingConfigurer: Routing.() -> Unit) {
        application.install(CallLogging)
        application.install(Routing, routingConfigurer)
    }

    protected fun createAndStartServer(log: Logger? = null, routingConfigurer: Routing.() -> Unit): THost {
        var lastFailures = emptyList<Throwable>()
        for (attempt in 1..5) {
            val server = createServer(log) {
                features(this, routingConfigurer)
            }

            val failures = startServer(server)
            if (failures.isEmpty()) {
                return server
            } else if (failures.any { it is BindException }) {
                port = findFreePort()
                sslPort = findFreePort()
                server.stop(1L, 1L, TimeUnit.SECONDS)
                lastFailures = failures
            } else {
                server.stop(1L, 1L, TimeUnit.SECONDS)
                throw MultipleFailureException(failures)
            }
        }

        throw MultipleFailureException(lastFailures)
    }

    private fun startServer(server: THost): List<Throwable> {
        this.server = server

        val l = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<Throwable>()

        val starting = launch(CommonPool + CoroutineExceptionHandler { _, _ -> }) {
            l.countDown()
            server.start()
        }
        l.await()

        val waitForPorts = launch(CommonPool) {
            server.environment.connectors.forEach { connector ->
                waitForPort(connector.port)
            }
        }

        starting.invokeOnCompletion { t ->
            if (t != null) {
                failures.add(t)
                waitForPorts.cancel()
            }
        }

        runBlocking {
            waitForPorts.join()
        }

        return failures
    }

    protected fun findFreePort() = ServerSocket(0).use { it.localPort }
    protected fun withUrl(path: String, builder: RequestBuilder.() -> Unit = {}, block: suspend HttpResponse.(Int) -> Unit) {
        withUrl(URL("http://127.0.0.1:$port$path"), port, builder, block)

        if (enableSsl) {
            withUrl(URL("https://127.0.0.1:$sslPort$path"), sslPort, builder, block)
        }

        if (enableHttp2 && enableSsl) {
            withHttp2(URL("https://127.0.0.1:$sslPort$path"), sslPort, builder, block)
        }
    }

    private fun withUrl(url: URL, port: Int, builder: RequestBuilder.() -> Unit, block: suspend HttpResponse.(Int) -> Unit) {
        runBlocking {
            withTimeout(timeout.seconds, TimeUnit.SECONDS) {
                DefaultHttpClient.request(url, {
                    this.sslSocketFactory = Companion.sslSocketFactory
                    builder()
                }).use { response ->
                    block(response, port)
                }
            }
        }
    }

    private fun withHttp2(url: URL, port: Int, builder: RequestBuilder.() -> Unit, block: suspend HttpResponse.(Int) -> Unit) {
        runBlocking {
            withTimeout(timeout.seconds, TimeUnit.SECONDS) {
                Http2Client.request(url, builder).use { response ->
                    block(response, port)
                }
            }
        }
    }

    class PublishedTimeout(val seconds: Long) : Timeout(seconds, TimeUnit.SECONDS)

    companion object {
        val keyStoreFile = File("build/temp.jks")
        lateinit var keyStore: KeyStore
        lateinit var sslSocketFactory: SSLSocketFactory

        @BeforeClass
        @JvmStatic
        fun setupAll() {
            keyStore = generateCertificate(keyStoreFile)
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(keyStore)
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, tmf.trustManagers, null)
            sslSocketFactory = ctx.socketFactory
        }

        private suspend fun CoroutineScope.waitForPort(port: Int) {
            do {
                delay(50)
                try {
                    Socket("localhost", port).close()
                    break
                } catch (expected: IOException) {
                }
            } while (isActive)
        }
    }
}