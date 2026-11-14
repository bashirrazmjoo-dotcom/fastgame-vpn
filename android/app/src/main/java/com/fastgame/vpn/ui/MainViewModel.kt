package com.fastgame.vpn.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fastgame.vpn.AppContainer
import com.fastgame.vpn.BuildConfig
import com.fastgame.vpn.data.Account
import com.fastgame.vpn.data.ApiException
import com.fastgame.vpn.data.AppConfig
import com.fastgame.vpn.data.VpnServerInfo
import com.fastgame.vpn.vpn.ConnectionState
import com.fastgame.vpn.vpn.Phase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LoadError { NETWORK, UPDATE_REQUIRED }

data class HomeUiState(
    val loading: Boolean = true,
    val error: LoadError? = null,
    val config: AppConfig? = null,
    val account: Account? = null,
    val servers: List<VpnServerInfo> = emptyList(),
    val selectedServerId: Int? = null,
    val showDisclosure: Boolean = false,
) {
    val selectedServer: VpnServerInfo? get() = servers.firstOrNull { it.id == selectedServerId }
}

class MainViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(HomeUiState())
    val ui: StateFlow<HomeUiState> = _ui.asStateFlow()
    val connection: StateFlow<ConnectionState> = container.connection.state

    private var pendingAfterDisclosure = false

    init {
        load()
        // Refresh the account whenever a session ends so the remaining time is current.
        viewModelScope.launch {
            var previous = Phase.IDLE
            container.connection.state.collect { s ->
                if (previous != Phase.IDLE && s.phase == Phase.IDLE) refreshAccount()
                previous = s.phase
            }
        }
    }

    fun load() {
        _ui.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val config = container.backend.config()
                if (config.minSupportedVersionCode > BuildConfig.APP_VERSION_CODE) {
                    _ui.update { it.copy(loading = false, error = LoadError.UPDATE_REQUIRED, config = config) }
                    return@launch
                }
                val account = container.backend.account()
                val servers = container.backend.servers()
                val last = container.prefs.lastServerId
                val selected = servers.firstOrNull { it.id == last }?.id ?: servers.firstOrNull()?.id
                _ui.update {
                    it.copy(loading = false, config = config, account = account, servers = servers, selectedServerId = selected)
                }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = LoadError.NETWORK) }
            }
        }
    }

    fun refreshAccount() {
        viewModelScope.launch {
            try {
                val account = container.backend.account()
                _ui.update { it.copy(account = account) }
            } catch (_: ApiException) {
            } catch (_: Exception) {
            }
        }
    }

    fun selectServer(id: Int) {
        container.prefs.lastServerId = id
        _ui.update { it.copy(selectedServerId = id) }
        // Picking another server while connected: drop the current session and connect to the new one.
        val conn = container.connection
        val now = conn.state.value
        if (now.phase != Phase.IDLE && now.serverId != id) {
            viewModelScope.launch {
                conn.disconnect()
                conn.state.first { it.phase == Phase.IDLE }
                repeat(10) {
                    if (startConnection()) return@launch
                    delay(150)
                }
            }
        }
    }

    /** Returns true when the UI may go on to ask for VPN permission and start the service. */
    fun requestConnect(): Boolean {
        if (!container.prefs.disclosureAccepted) {
            pendingAfterDisclosure = true
            _ui.update { it.copy(showDisclosure = true) }
            return false
        }
        return _ui.value.selectedServer != null
    }

    /** Called after the disclosure is accepted; true if the connect flow should continue. */
    fun acceptDisclosure(): Boolean {
        container.prefs.disclosureAccepted = true
        _ui.update { it.copy(showDisclosure = false) }
        val go = pendingAfterDisclosure
        pendingAfterDisclosure = false
        return go && _ui.value.selectedServer != null
    }

    fun declineDisclosure() {
        pendingAfterDisclosure = false
        _ui.update { it.copy(showDisclosure = false) }
    }

    fun startConnection(): Boolean {
        val server = _ui.value.selectedServer ?: return false
        return container.connection.connect(server)
    }

    fun disconnect() = container.connection.disconnect()
    fun dismissNotice() = container.connection.dismissNotice()
}
