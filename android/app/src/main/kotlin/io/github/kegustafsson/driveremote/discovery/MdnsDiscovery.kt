package io.github.kegustafsson.driveremote.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import io.github.kegustafsson.driveremote.core.ServerAddress
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** A Signal K server found on the network. */
data class DiscoveredServer(
  val address: ServerAddress,
  /** The advertised service name, e.g. "signalk-server on boat". */
  val name: String,
  /** TXT `self`: the vessel URN, if advertised. */
  val vesselUrn: String?,
  /** TXT `swname`/`swvers`, if advertised. */
  val software: String?,
)

/**
 * mDNS discovery of Signal K servers, via `NsdManager`.
 *
 * Discovery is a **convenience, never a requirement**. Boat access points not
 * uncommonly block multicast, some Android builds have long-standing NsdManager
 * quirks, and a server on a different subnet will not appear at all. Manual
 * host/port entry is always available and is the fallback the UI keeps in front
 * of the operator rather than hiding behind a failed scan.
 */
class MdnsDiscovery(context: Context) {

  private val appContext = context.applicationContext
  private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
  private val wifiManager =
    appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

  /**
   * Browse for Signal K servers until the collector stops.
   *
   * Emits the cumulative set, so a collector always has the whole list rather
   * than having to accumulate events itself.
   */
  fun discover(): Flow<List<DiscoveredServer>> = callbackFlow {
    // Some devices drop multicast packets to save power unless a lock is held.
    // Without this, discovery silently finds nothing on exactly the phones most
    // likely to be used at a helm.
    val multicastLock =
      wifiManager.createMulticastLock("drive-remote-mdns").apply {
        setReferenceCounted(true)
        runCatching { acquire() }
      }

    val found = LinkedHashMap<String, DiscoveredServer>()

    // resolveService() is single-flight on API < 34: a second call while one is
    // in progress throws "listener already in use". Resolutions are therefore
    // queued and run one at a time. (API 34+ has registerServiceInfoCallback,
    // which does not have this problem; the queue is harmless there.)
    val pending = ConcurrentLinkedQueue<NsdServiceInfo>()
    val resolving = AtomicBoolean(false)

    fun resolveNext() {
      if (!resolving.compareAndSet(false, true)) return
      val next = pending.poll()
      if (next == null) {
        resolving.set(false)
        return
      }
      nsdManager.resolveService(
        next,
        object : NsdManager.ResolveListener {
          override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            // A server we cannot resolve simply does not appear. Manual entry
            // still reaches it, so this is not worth surfacing as an error.
            resolving.set(false)
            resolveNext()
          }

          override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            serviceInfo.toDiscoveredServer()?.let { server ->
              found[server.address.toString()] = server
              trySend(found.values.toList())
            }
            resolving.set(false)
            resolveNext()
          }
        },
      )
    }

    val listener =
      object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
          // Nothing to report to the operator beyond an empty list: the manual
          // entry field is already the answer.
          close()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
          pending.add(serviceInfo)
          resolveNext()
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
          // Removed by name; the address is only known after resolution, so a
          // lost-but-unresolved service was never in the map anyway.
          found.entries.removeAll { it.value.name == serviceInfo.serviceName }
          trySend(found.values.toList())
        }
      }

    nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)

    awaitClose {
      runCatching { nsdManager.stopServiceDiscovery(listener) }
      runCatching { multicastLock.release() }
    }
  }

  private fun NsdServiceInfo.toDiscoveredServer(): DiscoveredServer? {
    val host = host?.hostAddress ?: return null
    val address = runCatching { ServerAddress(host, port) }.getOrNull() ?: return null
    return DiscoveredServer(
      address = address,
      name = serviceName ?: host,
      vesselUrn = txt("self"),
      software = listOfNotNull(txt("swname"), txt("swvers")).joinToString(" ").ifBlank { null },
    )
  }

  private fun NsdServiceInfo.txt(key: String): String? =
    attributes?.get(key)?.let { String(it, Charsets.UTF_8) }?.ifBlank { null }

  private companion object {
    /**
     * The Signal K HTTP interface. The spec also defines `_signalk-ws._tcp`,
     * but the HTTP record is what carries the port this app needs: it derives
     * the WebSocket URL from the same host and port, exactly as a browser
     * loading the web UI would.
     */
    const val SERVICE_TYPE = "_signalk-http._tcp."
  }
}
