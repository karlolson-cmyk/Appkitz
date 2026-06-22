package com.appbackup.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appbackup.data.model.AppInfo
import com.appbackup.data.model.AppType
import com.appbackup.data.pref.PreferencesManager
import com.appbackup.data.repository.AppRepository
import com.appbackup.data.repository.WebDavRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class BackupState {
    data object Idle : BackupState()
    data class InProgress(val currentApp: String, val progress: Float) : BackupState()
    data class Completed(val results: Map<AppInfo, String>) : BackupState()
    data class Error(val message: String) : BackupState()
}

class AppListViewModel(application: Application) : AndroidViewModel(application) {

    private val appRepository = AppRepository(application)
    private val webDavRepository = WebDavRepository()

    private val _appList = MutableStateFlow<List<AppInfo>>(emptyList())
    val appList: StateFlow<List<AppInfo>> = _appList.asStateFlow()

    private val _selectedTab = MutableStateFlow(AppType.USER)
    val selectedTab: StateFlow<AppType> = _selectedTab.asStateFlow()

    private val _backupState = MutableStateFlow<BackupState>(BackupState.Idle)
    val backupState: StateFlow<BackupState> = _backupState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val filteredAppList: StateFlow<List<AppInfo>> = combine(
        _appList, _selectedTab, _searchQuery
    ) { apps, tab, query ->
        val tabFiltered = apps.filter { it.type == tab }
        if (query.isBlank()) tabFiltered
        else tabFiltered.filter {
            it.name.contains(query, ignoreCase = true) ||
            it.packageName.contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tabTotalCount: StateFlow<Int> = combine(_appList, _selectedTab) { apps, tab ->
        apps.count { it.type == tab }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val tabSelectedCount: StateFlow<Int> = combine(_appList, _selectedTab) { apps, tab ->
        apps.count { it.type == tab && it.isSelected }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalSelectedCount: StateFlow<Int> = _appList.map { apps ->
        apps.count { it.isSelected }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val userTotalCount: StateFlow<Int> = _appList.map { apps ->
        apps.count { it.type == AppType.USER }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val systemTotalCount: StateFlow<Int> = _appList.map { apps ->
        apps.count { it.type == AppType.SYSTEM }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val userSelectedCount: StateFlow<Int> = _appList.map { apps ->
        apps.count { it.type == AppType.USER && it.isSelected }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val systemSelectedCount: StateFlow<Int> = _appList.map { apps ->
        apps.count { it.type == AppType.SYSTEM && it.isSelected }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private var webDavConfig: PreferencesManager.WebDavConfig? = null
    private var backupJob: Job? = null

    fun setWebDavConfig(config: PreferencesManager.WebDavConfig) {
        webDavConfig = config
    }

    fun loadApps() {
        viewModelScope.launch {
            _isLoading.value = true
            val userApps = withContext(Dispatchers.IO) { appRepository.getInstalledApps() }
            val systemApps = withContext(Dispatchers.IO) { appRepository.getSystemApps() }
            _appList.value = userApps + systemApps
            _isLoading.value = false
        }
    }

    fun setTab(tab: AppType) {
        _selectedTab.value = tab
    }

    fun toggleSelect(packageName: String) {
        _appList.value = _appList.value.map {
            if (it.packageName == packageName) it.copy(isSelected = !it.isSelected) else it
        }
    }

    fun selectAll(selected: Boolean) {
        val tab = _selectedTab.value
        _appList.value = _appList.value.map {
            if (it.type == tab) it.copy(isSelected = selected) else it
        }
    }

    fun getSelectedApps(): List<AppInfo> = _appList.value.filter { it.isSelected }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun backupSelected() {
        backupSelectedWithApk(true)
    }

    fun backupSelectedNoApk() {
        backupSelectedWithApk(false)
    }

    private fun backupSelectedWithApk(includeApk: Boolean) {
        val config = webDavConfig ?: run {
            _backupState.value = BackupState.Error("请先配置 WebDAV")
            return
        }
        val selected = getSelectedApps()
        if (selected.isEmpty()) {
            _backupState.value = BackupState.Error("请选择要备份的应用")
            return
        }

        backupJob = viewModelScope.launch {
            _backupState.value = BackupState.InProgress("准备中...", 0f)
            val result = webDavRepository.backupApps(selected, config, includeApk) { appName, progress ->
                _backupState.value = BackupState.InProgress(appName, progress)
            }
            _backupState.value = result.fold(
                onSuccess = { BackupState.Completed(it) },
                onFailure = { BackupState.Error(it.message ?: "备份失败") }
            )
        }
    }

    fun cancelBackup() {
        backupJob?.cancel()
        _backupState.value = BackupState.Idle
    }

    fun resetBackupState() {
        _backupState.value = BackupState.Idle
    }
}
