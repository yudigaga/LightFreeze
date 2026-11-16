package io.github.yudigaga.lightfreeze.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yudigaga.lightfreeze.BuildConfig
import io.github.yudigaga.lightfreeze.R
import io.github.yudigaga.lightfreeze.control.AppEntry
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.LocalContentColor

/** Presentation only: every write returns to the existing foreground controller. */
class MiuixMain(
    private val host: MainActivity,
    private val tags: TagUi,
    private val highRisk: HighRiskUi,
) {
    private val bridge get() = host.miuix()

    private data class AppRow(val app: AppEntry, val detail: String, val available: Boolean)
    private data class Catalog(
        val home: Boolean,
        val query: String,
        val searchVisible: Boolean,
        val tabs: List<Pair<String, String>>,
        val tabId: String,
        val rows: List<AppRow>,
        val selected: Set<String>,
        val filter: String,
        val empty: String,
        val writable: Boolean,
    )

    fun catalog() {
        val home = host.uiDestination() == MainActivity.HOME
        val state = Catalog(
            home, host.uiQuery(), host.uiSearchVisible(), host.uiTabs().map { it.key to it.value },
            host.uiSelectedTab(), host.uiVisibleEntries().map { AppRow(it, host.uiStateLabel(it), host.uiAvailable(it)) },
            host.uiSelected(), host.uiFilterLabel(), host.uiEmptyMessage(), host.uiWritesAllowed(),
        )
        bridge.page(if (state.selected.isEmpty()) if (home) "轻冻" else "应用" else "已选择 ${state.selected.size} 项", false, host.uiDestination()) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                CatalogContent(state, Modifier.widthIn(max = 840.dp).fillMaxSize())
            }
        }
    }

    @Composable
    private fun CatalogContent(state: Catalog, modifier: Modifier) {
        val enabled = !LocalUiBusy.current
        Column(modifier) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${state.rows.size} 个应用", Modifier.weight(1f), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                IconButton(onClick = host::uiToggleSearch, enabled = enabled) { MiuixUi.ResourceIcon(R.drawable.ic_search, "搜索") }
                IconButton(onClick = host::uiFilters, enabled = enabled) { MiuixUi.ResourceIcon(R.drawable.ic_filter, "筛选") }
                IconButton(onClick = host::uiCatalogMenu, enabled = enabled) { MiuixUi.ResourceIcon(R.drawable.ic_more, "更多") }
            }
            if (state.searchVisible || state.query.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    MiuixUi.SearchField(state.query, host::uiSearch)
                }
            }
            if (state.home) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.tabs, key = { it.first }) { (id, title) ->
                        val active = id == state.tabId
                        Card(
                            modifier = Modifier.semantics { selected = active; contentDescription = "$title，长按打开标签操作" },
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            colors = CardDefaults.defaultColors(
                                color = if (active) MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.surfaceContainer,
                                contentColor = if (active) MiuixTheme.colorScheme.onPrimaryContainer else MiuixTheme.colorScheme.onSurfaceContainer,
                            ),
                            onClick = { if (enabled) host.uiSelectTab(id) },
                            onLongPress = { if (enabled) host.uiTabActions(id) },
                        ) { Text(title, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal) }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(state.filter, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1, modifier = Modifier.weight(1f))
                if (state.home) IconButton(onClick = { host.uiTabActions(state.tabId) }, enabled = enabled) {
                    MiuixUi.ResourceIcon(R.drawable.ic_folder, "当前标签操作")
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                if (state.rows.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                        Text(state.empty, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center)
                    }
                } else {
                    val columns = if (state.home) (maxWidth.value / 96).toInt().coerceIn(3, 8) else if (maxWidth >= 640.dp) 2 else 1
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = if (state.home) 88.dp else 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.rows, key = { it.app.packageName }) { row ->
                            AppCard(row, state.home, row.app.packageName in state.selected, state.selected.isNotEmpty())
                        }
                    }
                }
                if (state.home) FloatingActionButton(
                    onClick = { if (enabled && state.writable) host.uiFreezeVisible() },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).alpha(if (enabled && state.writable) 1f else .4f)
                        .semantics {
                            contentDescription = if (state.selected.isEmpty()) "冻结当前可见的已管理应用" else "冻结所选 ${state.selected.size} 个应用"
                            if (!enabled || !state.writable) disabled()
                        },
                ) {
                    CompositionLocalProvider(LocalContentColor provides MiuixTheme.colorScheme.onPrimary) {
                        MiuixUi.ResourceIcon(R.drawable.ic_freeze, "冻结")
                    }
                }
            }
            if (state.selected.isNotEmpty()) SelectionBar(state)
        }
    }

    @Composable
    private fun AppCard(row: AppRow, grid: Boolean, checked: Boolean, selectionActive: Boolean) {
        val app = row.app
        val enabled = !LocalUiBusy.current
        Card(
            modifier = Modifier.fillMaxWidth().semantics {
                selected = checked
                contentDescription = "${app.label}，${app.packageName}，${row.detail}${if (checked) "，已选择" else ""}"
            },
            insideMargin = if (grid) PaddingValues(8.dp) else PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            colors = CardDefaults.defaultColors(color = if (checked) MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.surfaceContainer),
            onClick = { if (enabled) host.uiAppClick(app) },
            onLongPress = { if (enabled) host.uiToggleSelected(app) },
        ) {
            if (grid) Column(
                Modifier.fillMaxWidth().heightIn(min = 126.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.alpha(if (app.frozen) .45f else 1f)) { MiuixUi.AppIcon(host, app.packageName, 48.dp) }
                    if (selectionActive) Checkbox(
                        state = if (checked) ToggleableState.On else ToggleableState.Off,
                        onClick = { host.uiToggleSelected(app) },
                        modifier = Modifier.align(Alignment.TopEnd).size(28.dp), enabled = enabled,
                    )
                }
                Text(app.label, style = MiuixTheme.textStyles.body2, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (checked) "已选择" else if (app.frozen) "已冻结" else if (row.available) "可用" else "外部限制",
                    style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.alpha(if (app.frozen) .45f else 1f)) { MiuixUi.AppIcon(host, app.packageName) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(app.packageName, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(row.detail, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Checkbox(
                    state = if (checked) ToggleableState.On else ToggleableState.Off,
                    onClick = { host.uiToggleSelected(app) },
                    enabled = enabled,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "选择“${app.label}”进行批量操作" },
                )
            }
        }
    }

    @Composable
    private fun SelectionBar(state: Catalog) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("已选择 ${state.selected.size} 个应用", style = MiuixTheme.textStyles.footnote1)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { MiuixUi.Action("全选结果", host::uiSelectAll) }
                item { MiuixUi.Action(tags.assignmentAction(state.selected), host::uiAssignSelection, enabled = state.writable) }
                item { MiuixUi.Action("冻结", { host.uiBatch(true) }, enabled = state.writable) }
                item { MiuixUi.Action("解冻", { host.uiBatch(false) }, enabled = state.writable) }
                item { MiuixUi.Action("移出管理", host::uiRemoveSelection, enabled = state.writable) }
                item { MiuixUi.Action("取消", host::uiCancelSelection) }
            }
        }
    }

    fun catalogMenu() = bridge.dialog("更多操作") {
        MenuAction("标签管理", host::uiTags)
        if (host.uiDestination() == MainActivity.HOME) MenuAction("当前标签操作") { host.uiTabActions(host.uiSelectedTab()) }
        MenuAction("新建标签", host::uiNewTag)
        MenuAction("排序与隐藏", host::uiLayout)
        MenuAction("导入标签", host::uiImport)
        MenuAction("刷新应用状态", host::uiRefresh)
        MiuixUi.Action("关闭", bridge::dismissDialog, modifier = Modifier.fillMaxWidth())
    }

    fun tabActions(id: String, name: String) = bridge.dialog("$name · 标签操作") {
        MenuAction("添加应用到此标签") { tags.addMembers(id) }
        MenuAction("管理标签") { tags.openTag(id) }
        MenuAction("导出标签配置") { tags.exportTag(id) }
        MenuAction("排序与隐藏", host::uiLayout)
        MenuAction("新建标签", host::uiNewTag)
        MenuAction("导入标签", host::uiImport)
        MiuixUi.Action("关闭", bridge::dismissDialog, modifier = Modifier.fillMaxWidth())
    }

    @Composable
    private fun MenuAction(title: String, action: () -> Unit) {
        BasicComponent(title = title, enabled = !LocalUiBusy.current, onClick = { bridge.dismissDialog(); action() })
    }

    fun filters() {
        val initial = host.uiFilterFlags()
        bridge.dialog("筛选应用") {
            var draft by remember { mutableStateOf(initial.toList()) }
            listOf("三方", "官方（系统预装应用）", "已冻结", "未冻结").forEachIndexed { index, label ->
                CheckRow(label, draft[index]) { draft = draft.toMutableList().apply { set(index, !get(index)) } }
            }
            MiuixUi.Action("重置", { draft = listOf(true, true, false, false) }, modifier = Modifier.fillMaxWidth())
            MiuixUi.DialogActions("确定", { bridge.dismissDialog(); host.uiSetFilters(draft.toBooleanArray()) }, bridge::dismissDialog)
        }
    }

    @Composable
    private fun CheckRow(label: String, checked: Boolean, onClick: () -> Unit) {
        BasicComponent(
            title = label,
            enabled = !LocalUiBusy.current,
            onClick = onClick,
            endActions = { Checkbox(if (checked) ToggleableState.On else ToggleableState.Off, onClick, enabled = !LocalUiBusy.current) },
        )
    }

    fun settings() {
        val settings = host.uiSettings()
        val actual = host.uiSystemStatus()
        val writable = host.uiWritesAllowed()
        val root = settings.backend == "root"
        val entries = host.uiEntries()
        val backend = host.uiBackendStatus()
        bridge.page("设置", false, MainActivity.SETTINGS) { padding ->
            ScrollPage(padding) {
                MiuixUi.Section("工作模式")
                Card(insideMargin = PaddingValues(0.dp)) {
                    Preference("操作权限", if (root) "Root" else "Shizuku", R.drawable.ic_power, action = host::uiBackendDialog)
                    Preference(if (root) "检查 Root 授权" else "请求 Shizuku 授权", backend, R.drawable.ic_shield, action = host::uiRequestPermission)
                    val version = actual.getString("moduleVersionName") ?: "版本未知"
                    val module = if (!actual.getBoolean("ready")) "尚未就绪 · 点击查看与刷新" else "系统模块 $version" + if (actual.getInt("moduleVersionCode") < BuildConfig.VERSION_CODE) " · 请重启加载更新" else " · 已就绪"
                    Preference("系统增强", module, R.drawable.ic_info) { systemInfo() }
                }
                MiuixUi.Section("标签与保护")
                Card(insideMargin = PaddingValues(0.dp)) {
                    Preference("标签管理", "添加与移动应用、回冻规则、排序隐藏和本地导入导出", R.drawable.ic_folder, action = host::uiTags)
                    Preference("锁屏回冻延迟", "${settings.lockDelayMs / 1000} 秒 · 所有标签共用，划卡不等待此延迟", R.drawable.ic_freeze, enabled = writable) { lockDelay() }
                    MiuixUi.ToggleRow("媒体与通话保护", "播放或通话时暂缓自动回冻；手动冻结仍可中断任务", settings.protectMedia, host::uiSetProtectMedia, writable)
                    MiuixUi.ToggleRow("最近任务保护", "应用仍有任务卡片时，跳过手动、自动和 URL 冻结", settings.protectRecents, host::uiSetProtectRecents, writable)
                }
                MiuixUi.Section("高危放行")
                Card(insideMargin = PaddingValues(12.dp)) { highRisk.Settings(entries) }
                MiuixUi.Section("URL 调用")
                Card(insideMargin = PaddingValues(12.dp)) { tags.UrlSettings() }
                MiuixUi.Section("轻冻本体")
                Card(insideMargin = PaddingValues(0.dp)) {
                    MiuixUi.ToggleRow(
                        "冻结模块 App 本体", "只冻结轻冻，系统标签规则保留；有效 URL 可临时唤醒并回冻", settings.selfFreeze,
                        host::uiSelfFreeze, writable && (actual.getBoolean("ready") && actual.getBoolean("selfFreezeSupported") || settings.selfFreeze),
                    )
                    Preference("保持本体解冻", "取消本体待回冻，不取消目标应用规则", R.drawable.ic_restore, action = host::uiKeepEnabled)
                    Preference("本体 URL 入口", if (actual.getBoolean("frozenUrlSupported")) "已就绪" else actual.getString("frozenUrlReason", "更新模块并重启后检查"), R.drawable.ic_info) {
                        info("本体恢复与 URL", actual.getString("selfFreezeReason", "需已加载的 API 102 系统模块。请保留外部 Root/ADB 恢复方式；本包桌面快捷方式不能唤醒禁用本体。"))
                    }
                }
                MiuixUi.Section("恢复")
                Card(insideMargin = PaddingValues(0.dp)) {
                    Preference("恢复全部已冻结应用", "仅处理轻冻有效恢复记录，标签规则仍会生效", R.drawable.ic_restore, enabled = writable, action = host::uiRestoreAll)
                    Preference("导出恢复清单", "核对包名、安装身份、原始状态和外部恢复命令", R.drawable.ic_more, action = host::uiExportRecovery)
                }
            }
        }
    }

    @Composable
    private fun ScrollPage(padding: PaddingValues, body: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 840.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = body,
            )
        }
    }

    @Composable
    private fun Preference(title: String, summary: String, icon: Int, enabled: Boolean = true, action: () -> Unit) {
        BasicComponent(
            title = title, summary = summary,
            startAction = { MiuixUi.ResourceIcon(icon) }, endActions = { MiuixUi.ResourceIcon(R.drawable.ic_chevron_right) },
            enabled = enabled && !LocalUiBusy.current, onClick = action,
        )
    }

    fun backend() {
        val current = if (host.uiSettings().backend == "root") 1 else 0
        bridge.dialog("操作权限") {
            listOf("Shizuku", "Root").forEachIndexed { index, name ->
                BasicComponent(
                    title = name, enabled = !LocalUiBusy.current,
                    onClick = { bridge.dismissDialog(); host.uiChooseBackend(index) },
                    endActions = { RadioButton(index == current, { bridge.dismissDialog(); host.uiChooseBackend(index) }, enabled = !LocalUiBusy.current) },
                )
            }
            MiuixUi.Action("取消", bridge::dismissDialog, modifier = Modifier.fillMaxWidth())
        }
    }

    private fun lockDelay() {
        val current = host.uiSettings().lockDelayMs
        val values = listOf(0L, 3000L, 10000L, 30000L)
        bridge.dialog("锁屏回冻延迟") {
            values.forEach { value ->
                val select = { bridge.dismissDialog(); host.uiChooseLockDelay(value) }
                BasicComponent(
                    title = if (value == 0L) "0 秒（事件后校验）" else "${value / 1000} 秒",
                    enabled = !LocalUiBusy.current, onClick = select,
                    endActions = { RadioButton(current == value, select, enabled = !LocalUiBusy.current) },
                )
            }
            MiuixUi.Action("取消", bridge::dismissDialog, modifier = Modifier.fillMaxWidth())
        }
    }

    private fun systemInfo() = bridge.dialog("系统增强状态") {
        SelectionContainer { MiuixUi.Description(host.uiSystemStatus().getString("message", "尚未收到系统回执。请在 API 102 框架中启用模块，作用域选择 Android 系统，并重启。")) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MiuixUi.Action("关闭", bridge::dismissDialog, modifier = Modifier.weight(1f))
            MiuixUi.Action("刷新", { bridge.dismissDialog(); host.uiRefresh() }, primary = true, modifier = Modifier.weight(1f))
        }
    }

    fun detail(app: AppEntry) {
        val managed: Boolean
        val rule: String
        try { managed = host.uiManaged(app); rule = host.uiRuleLabel(app) }
        catch (error: RuntimeException) { info("无法读取应用规则", error.message ?: error.javaClass.simpleName); return }
        bridge.dialog(app.label) {
            SelectionContainer { MiuixUi.Description("${app.packageName}\n用户 ${app.userId} · ${host.uiStateLabel(app)}") }
            MiuixUi.Description(if (managed) "已纳入管理。移出管理需通过下方的明确操作，解冻成功后才移除归属。" else "尚未纳入管理。可仅纳入管理，或通过冻结操作纳入并冻结。")
            MiuixUi.Description("有效回冻规则：$rule。切换视图或操作入口不会覆盖此规则。")
            if (!managed) DetailAction("仅纳入管理") { host.uiManage(app) }
            DetailAction("解冻", true) { host.uiUnfreeze(app) }
            DetailAction(if (managed) "冻结" else "纳入管理并冻结") { host.uiFreeze(app) }
            DetailAction("打开应用") { host.uiOpen(app) }
            DetailAction(tags.assignmentAction(setOf(app.packageName)) + "…") { host.uiAssign(app) }
            if (managed) DetailAction("移出管理并解冻") { host.uiRemove(app) }
            MiuixUi.Description("冻结使用 PM 禁用应用；解冻也可启用其他工具禁用的应用，并采用所属标签规则。挂起、隐藏和企业策略限制需由相应工具解除。")
            DetailAction("添加打开快捷方式") { host.uiPin(app) }
            MiuixUi.Section("单应用 URL")
            MiuixUi.Description("所有链接和快捷方式均采用各应用所属标签的规则；未分组时采用「未分组默认规则」。标签或视图链接只决定本次操作哪些应用。")
            tags.TargetLinks("package", app.packageName)
            MiuixUi.Action("关闭", bridge::dismissDialog, modifier = Modifier.fillMaxWidth())
        }
    }

    @Composable
    private fun DetailAction(title: String, primary: Boolean = false, action: () -> Unit) {
        MiuixUi.Action(title, { bridge.dismissDialog(); action() }, enabled = host.uiWritesAllowed(), primary = primary, modifier = Modifier.fillMaxWidth())
    }

    fun confirm(title: String, message: String, positive: String, confirmed: Runnable) = bridge.dialog(title) {
        SelectionContainer { MiuixUi.Description(message) }
        MiuixUi.DialogActions(positive, { bridge.dismissDialog(); confirmed.run() }, bridge::dismissDialog)
    }

    fun info(title: String, message: String) = bridge.dialog(title) {
        SelectionContainer { MiuixUi.Description(message) }
        MiuixUi.Action("关闭", bridge::dismissDialog, primary = true, modifier = Modifier.fillMaxWidth())
    }

    fun recovery(text: String) = bridge.dialog("恢复清单") {
        SelectionContainer { MiuixUi.Description(text) }
        MiuixUi.Action("复制", { host.uiCopy("轻冻恢复清单", text) }, modifier = Modifier.fillMaxWidth())
        MiuixUi.Action("导出", { bridge.dismissDialog(); host.uiShareRecovery(text) }, primary = true, modifier = Modifier.fillMaxWidth())
        MiuixUi.Action("关闭", bridge::dismissDialog, modifier = Modifier.fillMaxWidth())
    }

    fun confirmSelfFreeze(command: String, confirmed: Runnable) = bridge.dialog("冻结轻冻本体并退出？") {
        SelectionContainer {
            MiuixUi.Description("仅禁用本工具，保留系统回冻规则。有效 URL 可经已就绪的系统入口临时唤醒，全部请求结束后再回冻；本包桌面快捷方式不能恢复本体。\n\n请保存外部恢复命令。已授权 ADB：\n$command\n\nRoot 终端执行同一命令时去掉 adb shell 前缀。本体跨重启注入须以当前系统实测为准。")
        }
        MiuixUi.Action("复制本体恢复命令", { host.uiCopy("轻冻本体恢复命令", command) }, modifier = Modifier.fillMaxWidth())
        MiuixUi.Action("已确认外部恢复，冻结本体", { bridge.dismissDialog(); confirmed.run() }, primary = true, modifier = Modifier.fillMaxWidth())
        MiuixUi.Action("取消", bridge::dismissDialog, modifier = Modifier.fillMaxWidth())
    }

    fun about() = bridge.page("关于", false, MainActivity.ABOUT) { padding ->
        ScrollPage(padding) {
            Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(painterResource(R.drawable.ic_launcher), "轻冻", Modifier.size(72.dp))
                Text("轻冻", style = MiuixTheme.textStyles.title2)
                MiuixUi.Description("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            }
            Card(insideMargin = PaddingValues(0.dp)) {
                BasicComponent(title = "按需冻结，清晰管理", summary = "Root / Shizuku · 所属标签规则 · API 102 系统增强", startAction = { MiuixUi.ResourceIcon(R.drawable.ic_freeze) })
                BasicComponent(title = "轻量运行", summary = "无 App 后台服务、无轮询、无网络权限；系统增强具有独立生命周期", startAction = { MiuixUi.ResourceIcon(R.drawable.ic_shield) })
            }
            MiuixUi.Section("项目")
            Card(insideMargin = PaddingValues(0.dp)) {
                Preference("项目仓库", "github.com/yudigaga/LightFreeze", R.drawable.ic_link, action = host::uiOpenRepository)
            }
            MiuixUi.Section("界面组件")
            Card(insideMargin = PaddingValues(0.dp)) {
                Preference("Miuix", "Compose Miuix 组件 · 壁纸动态取色 · 跟随系统深浅色", R.drawable.ic_link, action = host::uiOpenReference)
            }
            MiuixUi.Description("Miuix 使用 Apache-2.0 许可证。轻冻保留自己的名称、图标和冻结逻辑。")
        }
    }

    fun repository(url: String) = bridge.dialog("轻冻项目仓库") {
        SelectionContainer { MiuixUi.Description("当前没有可打开网页的应用，可以复制链接后查看。\n\n$url") }
        MiuixUi.Action("复制链接", { host.uiCopy("轻冻项目仓库", url) }, primary = true, modifier = Modifier.fillMaxWidth())
        MiuixUi.Action("关闭", bridge::dismissDialog, modifier = Modifier.fillMaxWidth())
    }
    fun reference(url: String) = bridge.dialog("Miuix 项目") {
        SelectionContainer { MiuixUi.Description("当前没有可打开网页的应用，可以复制链接后查看。\n\n$url") }
        MiuixUi.Action("复制链接", { host.uiCopy("Miuix 项目", url) }, primary = true, modifier = Modifier.fillMaxWidth())
        MiuixUi.Action("关闭", bridge::dismissDialog, modifier = Modifier.fillMaxWidth())
    }
}
