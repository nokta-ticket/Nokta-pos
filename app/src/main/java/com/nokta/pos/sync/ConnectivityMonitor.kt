package com.nokta.pos.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Diz se o terminal tem internet utilizável.
 *
 * Usa `NET_CAPABILITY_VALIDATED` além de `INTERNET`: no salão é comum a
 * maquininha continuar associada a um Wi-Fi que já não entrega tráfego (portal
 * cativo, roteador sem link). Só `INTERNET` diria "online" nesse caso e o
 * operador confiaria numa conexão que não existe — pior que dizer "offline".
 *
 * Isto é indicador de UI, não porteiro: nada no app deixa de tentar por causa
 * deste valor. Quem decide se uma operação foi para a fila é a falha real da
 * chamada (`IOException`), nunca este flag — a rede pode cair entre a leitura
 * daqui e o request.
 */
@Singleton
class ConnectivityMonitor @Inject constructor(
    private val context: Context,
) {
    private val manager: ConnectivityManager?
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    fun isOnline(): Boolean {
        val capabilities = manager?.let { it.getNetworkCapabilities(it.activeNetwork) } ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Emite a cada mudança real de conectividade. */
    fun observe(): Flow<Boolean> = callbackFlow {
        val cm = manager
        if (cm == null) {
            trySend(false)
            awaitClose { }
            return@callbackFlow
        }

        trySend(isOnline())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(isOnline()) }
            override fun onLost(network: Network) { trySend(isOnline()) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(isOnline())
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)

        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()
}
