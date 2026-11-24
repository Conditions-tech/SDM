package io.github.conditionstech.sdm.engine

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * An OkHttp client that obeys the [NetworkPolicy] (ADR-0007): checked DNS, no proxy, no
 * automatic redirects or retries, and a last check on the connected socket. The downloader
 * and every other SDM request (subtitle searches, M18) use it.
 *
 * [configure] is for tests only (to trust a test server's certificate); it runs before SDM's
 * own settings, so it can't switch the policy or the redirect checks off.
 */
fun policyClient(
    policy: NetworkPolicy = NetworkPolicy(),
    dns: Dns = Dns.SYSTEM,
    connectTimeoutMs: Long = 20_000,
    readTimeoutMs: Long = 30_000,
    configure: (OkHttpClient.Builder) -> Unit = {},
): OkHttpClient = OkHttpClient.Builder()
    .apply(configure)
    .dns(PolicyDns(policy, dns))
    .proxy(Proxy.NO_PROXY) // a proxy would hide the real destination from the policy
    .followRedirects(false) // followed by hand where needed: every hop is checked
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
    .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
    .addNetworkInterceptor { chain ->
        // Safety net: the address actually connected to, whatever led there.
        chain.connection()?.route()?.socketAddress?.address?.let {
            policy.check(it, chain.request().url.host)
        }
        chain.proceed(chain.request())
    }
    .build()
