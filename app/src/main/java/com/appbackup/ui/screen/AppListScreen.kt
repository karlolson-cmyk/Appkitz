package com.appbackup.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.appbackup.data.model.AppType
import com.appbackup.ui.component.AppCard
import com.appbackup.ui.component.BackupProgressDialog
import com.appbackup.viewmodel.AppListViewModel
import com.appbackup.viewmodel.BackupState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(
    viewModel: AppListViewModel,
    onConfigureWebDav: () -> Unit
) {
    val fullAppList by viewModel.appList.collectAsState()
    val filteredAppList by viewModel.filteredAppList.collectAsState()
    val backupState by viewModel.backupState.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val tabTotalCount by viewModel.tabTotalCount.collectAsState()
    val tabSelectedCount by viewModel.tabSelectedCount.collectAsState()
    val userTotalCount by viewModel.userTotalCount.collectAsState()
    val systemTotalCount by viewModel.systemTotalCount.collectAsState()
    val userSelectedCount by viewModel.userSelectedCount.collectAsState()
    val systemSelectedCount by viewModel.systemSelectedCount.collectAsState()
    val totalSelectedCount by viewModel.totalSelectedCount.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isScrollingDown by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val pagerState = rememberPagerState(pageCount = { 2 })
    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        viewModel.loadApps()
    }

    LaunchedEffect(pagerState.currentPage) {
        viewModel.setTab(if (pagerState.currentPage == 0) AppType.USER else AppType.SYSTEM)
        isScrollingDown = false
    }

    LaunchedEffect(totalSelectedCount) {
        if (totalSelectedCount > 0) {
            isScrollingDown = false
        }
    }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            delay(100)
            searchFocusRequester.requestFocus()
        }
    }

    fun copyPackageName(pkg: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("package_name", pkg))
        scope.launch {
            snackbarHostState.showSnackbar("已复制包名：$pkg", duration = SnackbarDuration.Short)
        }
    }

    when (val state = backupState) {
        is BackupState.InProgress -> {
            BackupProgressDialog(
                message = state.currentApp,
                progress = state.progress,
                onCancel = { viewModel.cancelBackup() }
            )
        }
        is BackupState.Completed -> {
            AlertDialog(
                onDismissRequest = { viewModel.resetBackupState() },
                title = { Text("备份完成") },
                text = {
                    Column {
                        state.results.forEach { (app, result) ->
                            Text("${app.name}: $result")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.resetBackupState() }) {
                        Text("确定")
                    }
                }
            )
        }
        is BackupState.Error -> {
            AlertDialog(
                onDismissRequest = { viewModel.resetBackupState() },
                title = { Text("备份出错") },
                text = { Text(state.message) },
                confirmButton = {
                    TextButton(onClick = { viewModel.resetBackupState() }) {
                        Text("确定")
                    }
                }
            )
        }
        else -> {}
    }

    val fabVisible = totalSelectedCount > 0 || !isScrollingDown

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        if (isSearchActive) {
                            TextField(
                                value = searchQuery,
                                onValueChange = { query ->
                                    searchQuery = query
                                    viewModel.setSearchQuery(query)
                                },
                                placeholder = { Text("搜索应用...") },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedContainerColor = Color.Transparent
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(searchFocusRequester)
                            )
                        } else {
                            Text("APP 备份")
                        }
                    },
                    actions = {
                        if (isSearchActive) {
                            IconButton(onClick = {
                                isSearchActive = false
                                searchQuery = ""
                                viewModel.setSearchQuery("")
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "关闭搜索")
                            }
                        } else {
                            IconButton(onClick = { isSearchActive = true }) {
                                Icon(Icons.Default.Search, contentDescription = "搜索")
                            }
                            IconButton(onClick = onConfigureWebDav) {
                                Icon(Icons.Default.Settings, contentDescription = "配置")
                            }
                        }
                    }
                )
            },
            floatingActionButton = {
                AnimatedVisibility(
                    visible = fabVisible,
                    enter = slideInVertically { it },
                    exit = slideOutVertically { it }
                ) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ExtendedFloatingActionButton(
                            onClick = { viewModel.backupSelectedNoApk() },
                            icon = { Icon(Icons.Default.Share, contentDescription = null) },
                            text = { Text("备份到 WebDAV") }
                        )
                        ExtendedFloatingActionButton(
                            onClick = { viewModel.backupSelected() },
                            icon = { Icon(Icons.Default.Done, contentDescription = null) },
                            text = { Text("备份到 WebDAV（含 APK）") }
                        )
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        TabRow(selectedTabIndex = if (selectedTab == AppType.USER) 0 else 1) {
                            Tab(
                                selected = selectedTab == AppType.USER,
                                onClick = {
                                    scope.launch { pagerState.animateScrollToPage(0) }
                                },
                                text = { Text("用户应用") }
                            )
                            Tab(
                                selected = selectedTab == AppType.SYSTEM,
                                onClick = {
                                    scope.launch { pagerState.animateScrollToPage(1) }
                                },
                                text = { Text("系统应用") }
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = tabSelectedCount == tabTotalCount && tabTotalCount > 0,
                                onCheckedChange = { viewModel.selectAll(it) }
                            )
                            Text(
                                "全选（用户: $userSelectedCount/$userTotalCount, 系统: $systemSelectedCount/$systemTotalCount）"
                            )
                        }

                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) { page ->
                            val currentTab = if (page == 0) AppType.USER else AppType.SYSTEM
                            val listState = rememberLazyListState()
                            var previousTotalScroll by remember { mutableIntStateOf(-1) }
                            val displayList = if (isSearchActive) filteredAppList
                                else fullAppList.filter { it.type == currentTab }

                            LaunchedEffect(listState) {
                                snapshotFlow {
                                    Pair(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                                }.collect { (index, offset) ->
                                    val currentTotal = index * 10000 + offset
                                    if (previousTotalScroll >= 0) {
                                        val diff = currentTotal - previousTotalScroll
                                        if (diff > 3) {
                                            isScrollingDown = true
                                        } else if (diff < -3) {
                                            isScrollingDown = false
                                        }
                                    }
                                    previousTotalScroll = currentTotal
                                }
                            }

                            LaunchedEffect(displayList) {
                                if (isSearchActive && displayList.isNotEmpty()) {
                                    listState.scrollToItem(0)
                                }
                            }

                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                verticalArrangement = Arrangement.Top
                            ) {
                                items(displayList, key = { it.packageName }) { app ->
                                    Column(Modifier.padding(bottom = 8.dp)) {
                                        AppCard(
                                            app = app,
                                            onToggle = { viewModel.toggleSelect(app.packageName) },
                                            onLongClick = { copyPackageName(app.packageName) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
        )
    }
}
