package com.example.ui

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.example.BuildConfig
import com.example.data.ProductionTraderRepository
import com.example.viewmodel.TraderViewModel
import kotlinx.coroutines.launch

/** Owner opt-in composition. No business limits or lease authority originate here. */
@Composable
internal fun OfflinePilotControls(repository: ProductionTraderRepository, viewModel: TraderViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var offline by remember(repository) { mutableStateOf(true) }
    var busy by remember(repository) { mutableStateOf(false) }
    var status by remember(repository) { mutableStateOf("") }
    val maxPages = BuildConfig.OFFLINE_CATALOG_MAX_PAGES.toIntOrNull()?.takeIf { it > 0 }
    val maxProducts = BuildConfig.OFFLINE_CATALOG_MAX_PRODUCTS.toIntOrNull()?.takeIf { it > 0 }
    val configured = BuildConfig.OFFLINE_OWNER_PILOT && maxPages != null && maxProducts != null
    DisposableEffect(repository, configured) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        fun update() {
            val online = manager.getNetworkCapabilities(manager.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            offline = !online
            viewModel.setNetworkOffline(!online)
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = update()
            override fun onLost(network: Network) = update()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update()
        }
        manager.registerDefaultNetworkCallback(callback)
        update()
        viewModel.setOfflineSalesCapabilityEnabled(configured)
        onDispose {
            manager.unregisterNetworkCallback(callback)
            repository.isOfflineSalesCapabilityEnabled = false
        }
    }
    Column {
        Text("Offline pilot / تجربة البيع دون اتصال")
        Text(if (offline) "Offline / دون اتصال" else "Online / متصل")
        Text("Pending synchronization / غير مرحّلة بعد: ${state.pendingOfflineSalesCount}")
        if (!configured) Text("Configure catalog preparation bounds / اضبط حدود تجهيز الكتالوج")
        Button(enabled = configured && !busy && !offline, onClick = {
            busy = true
            scope.launch {
                try {
                    val prepared = repository.prepareOfflineCatalog(requireNotNull(maxPages), requireNotNull(maxProducts)) {
                        status = "Prepared / تم تجهيز: $it"
                    }
                    val acquired = prepared.fold(
                        onSuccess = { repository.acquireOfflineSaleLease().mapCatching { lease ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                com.example.data.mobile.offline.RestrictedOfflineBootstrapStore(context)
                                    .save(repository, lease, repository.offlineLeaseStore)
                            }
                        } },
                        onFailure = { Result.failure(it) }
                    )
                    status = if (acquired.isSuccess) "Lease ready / الإذن جاهز" else
                        "Preparation failed / فشل التجهيز: ${acquired.exceptionOrNull()?.message.orEmpty()}"
                    viewModel.refreshOfflineLeaseState()
                } finally { busy = false }
            }
        }) { Text(if (busy) "Preparing / جار التجهيز" else "Prepare offline sales / تجهيز البيع دون اتصال") }
        if (status.isNotEmpty()) Text(status)
        Button(enabled = !busy && !offline, onClick = { viewModel.syncOfflineSales() }) {
            Text("Synchronize pending sales / مزامنة المبيعات المعلقة")
        }
    }
}
