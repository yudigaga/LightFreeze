package io.github.yudigaga.lightfreeze.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.github.yudigaga.lightfreeze.ui.MiuixUi.Action
import io.github.yudigaga.lightfreeze.ui.MiuixUi.AppIcon
import io.github.yudigaga.lightfreeze.ui.MiuixUi.Description
import io.github.yudigaga.lightfreeze.ui.MiuixUi.DialogActions
import io.github.yudigaga.lightfreeze.ui.MiuixUi.ResourceIcon
import io.github.yudigaga.lightfreeze.ui.MiuixUi.SearchField
import io.github.yudigaga.lightfreeze.ui.MiuixUi.Section
import io.github.yudigaga.lightfreeze.ui.MiuixUi.ToggleRow
import io.github.yudigaga.lightfreeze.R
import io.github.yudigaga.lightfreeze.automation.AutomationLinks
import io.github.yudigaga.lightfreeze.control.*
import io.github.yudigaga.lightfreeze.core.FreezeTriggers
import io.github.yudigaga.lightfreeze.core.SettingsStore
import io.github.yudigaga.lightfreeze.tags.*
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Presentation only. Configuration writes and provider I/O stay on Main's foreground worker. */
class TagUi(private val host: MainActivity, private val settings: SettingsStore) {
    fun interface Work { @Throws(Exception::class) fun run(): Runnable }
    private val links = AutomationLinks(host, settings)
    private val targets = TargetActions(host, settings)
    private var currentId: String? = null
    private var editingMembers = false
    private var pendingExportId: String? = null
    private var exportPickerLaunched = false
    private val exportDirectory get() = java.io.File(host.cacheDir, "export-drafts")
    fun restoreExport(id: String?) { pendingExportId = id; exportPickerLaunched = id != null }
    fun exportDraftId(): String? = if (exportPickerLaunched) pendingExportId else null
    /** Queue after existing document work; never remove an outstanding picker snapshot. */
    fun exportCleanupOnDestroy(): Runnable? {
        if (exportPickerLaunched) return null
        val id = pendingExportId ?: return null
        val directory = exportDirectory
        pendingExportId = null
        return Runnable {
            try { ExportDraft.remove(directory, id) }
            catch (_: Exception) { android.util.Log.w("LightFreeze", "未能清理已结束的导出草稿") }
        }
    }
    private val bridge get() = host.miuix()

    fun restore(id: String?) { currentId = id }
    fun detailId(): String? = currentId
    fun open() { currentId = null; editingMembers = false; show() }
    fun openTag(id: String) { currentId = id; editingMembers = false; show() }
    fun createTag() { if (!host.tagBusy()) editName(null) }
    fun addMembers(id: String) {
        if (host.tagBusy()) return
        val tag = find(id) ?: return host.tagMessage("标签已删除，请重新选择。", true)
        currentId = id; loadMembers(tag, true)
    }
    fun assignmentAction(packages: Set<String>): String = try {
        if (settings.tags.any { it.packages.any(packages::contains) }) "移动到标签" else "加入标签"
    } catch (_: RuntimeException) { "移动/加入标签" }
    private fun find(id: String?): Tag? = if (id == null) null else settings.tags.firstOrNull { it.id == id }
    private fun orderedTags(): List<Tag> {
        val remaining = settings.tags.associateByTo(linkedMapOf()) { it.id }
        return settings.homeTabOrder.mapNotNull { remaining.remove(it) } + remaining.values
    }
    private fun work(text: String, block: () -> Runnable) = host.tagTask(text, Work(block))
    private fun error(e: Exception): String = e.message ?: e.javaClass.simpleName
    private fun dismiss() = bridge.dismissDialog()
    private fun confirm(title: String, summary: String, verb: String, action: () -> Unit) {
        bridge.dialog(title) {
            Description(summary)
            DialogActions(verb, { if (!host.tagBusy()) { dismiss(); action() } }, ::dismiss)
        }
    }
    private fun alert(title: String, text: String) {
        bridge.dialog(title) { Description(text); Action("关闭", ::dismiss) }
    }

    fun addToTag(packages: Set<String>) {
        if (host.tagBusy()) return
        try {
            val chosen = packages.toSet()
            if (chosen.isEmpty()) return host.tagMessage("请先选择应用。", true)
            val choices = orderedTags()
            bridge.dialog("选择目标标签 · ${chosen.size} 个应用") {
                Column {
                    choices.forEach { tag ->
                        BasicComponent(title = tag.name, summary = "${tag.packages.size} 个应用", onClick = {
                            if (!host.tagBusy()) assignToTag(chosen, tag.id, null)
                        })
                    }
                    Action("新建标签并加入", { createTagAndAssign(chosen) })
                }
                Action("取消", ::dismiss)
            }
        } catch (e: RuntimeException) { host.tagMessage("无法选择标签：${error(e)}", true) }
    }

    private fun createTagAndAssign(packages: Set<String>) {
        bridge.dialog("新建标签并加入") {
            var name by remember { mutableStateOf("") }
            var failure by remember { mutableStateOf("") }
            SearchField(name, { name = it }, "标签名称（最多 40 个字符）")
            Description("保存后将 ${packages.size} 个应用加入新标签，已有归属会移到这里。可随后设置回冻条件。")
            if (failure.isNotEmpty()) Description(failure, true)
            DialogActions("保存", {
                if (!host.tagBusy()) try {
                    val created = Tag(UUID.randomUUID().toString(), name, FreezeTriggers.MANUAL, packages)
                    assignToTag(packages, created.id, created) { failure = it }
                } catch (e: RuntimeException) { failure = error(e) }
            }, ::dismiss)
        }
    }
    private fun assignToTag(packages: Set<String>, targetId: String, created: Tag?, failure: ((String) -> Unit)? = null) {
        work("正在加入标签并同步规则…") {
            try {
                synchronized(SettingsStore::class.java) {
                    val current = settings.tags
                    val candidate = if (created == null) TagCodec.addMembers(current, targetId, packages)
                        else TagCodec.upsertSingleMembership(current, created)
                    settings.validateTags(candidate); settings.tags = candidate
                }
            } catch (e: RuntimeException) {
                return@work Runnable { failure?.invoke(error(e)) ?: host.tagMessage("未加入标签：${error(e)}", true) }
            }
            val result = host.tagSyncConfig()
            Runnable {
                dismiss(); host.tagAssignmentsChanged()
                host.tagMessage("已更新 ${packages.size} 个应用的标签归属。" +
                    if (result.success) "" else "\n本地已保存；系统同步未完成：${result.message}", !result.success)
            }
        }
    }

    fun showBuiltinActions(id: String, export: Runnable) {
        if (!AppTabs.isBuiltin(id)) return
        val title = AppTabs.title(id); val wire = id.substring("builtin:".length)
        bridge.dialog("$title · 视图操作") {
            Column {
                Description("此视图只筛选操作目标。应用采用所属标签或未分组默认规则。URL、快捷方式和导出读取完整成员，不受搜索、筛选、排序或隐藏影响。")
                NavigationAction("导出完整成员快照", R.drawable.ic_export) { dismiss(); export.run() }
                TargetLinks("tab", wire)
                NavigationAction("添加冻结快捷方式", R.drawable.ic_shortcut) { pin("tab", wire, title, "freeze") }
                NavigationAction("添加解冻快捷方式", R.drawable.ic_shortcut) { pin("tab", wire, title, "unfreeze") }
                NavigationAction("排序与隐藏", R.drawable.ic_sort, layoutOnly = true) { dismiss(); showHomeTabLayout() }
                NavigationAction("新建标签", R.drawable.ic_add) { dismiss(); createTag() }
                NavigationAction("导入标签", R.drawable.ic_import) { dismiss(); requestImport() }
            }
            Action("关闭", ::dismiss)
        }
    }

    /** Layout is local presentation: it must remain editable when the body claim is unresolved. */
    fun showHomeTabLayout() {
        try {
            val titles = linkedMapOf<String, String>()
            val initialOrder: List<String>; val initialHidden: Set<String>
            synchronized(SettingsStore::class.java) {
                listOf(AppTabs.ALL, AppTabs.MANAGED, AppTabs.FROZEN, AppTabs.AVAILABLE).forEach { titles[it] = AppTabs.title(it) }
                settings.tags.forEach { titles[it.id] = it.name }
                initialOrder = settings.homeTabOrder.toList(); initialHidden = settings.hiddenHomeTabs.toSet()
            }
            bridge.dialog("排序与隐藏") {
                var order by remember { mutableStateOf(initialOrder) }
                var hidden by remember { mutableStateOf(initialHidden) }
                var saving by remember { mutableStateOf(false) }
                var failure by remember { mutableStateOf("") }
                Description("勾选显示，取消勾选隐藏；点击保存后生效，至少保留一项。仅调整首页布局。")
                Column {
                    order.forEachIndexed { index, id ->
                        key(id) {
                            val title = titles[id] ?: id
                            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                BasicComponent(modifier = Modifier.weight(1f), title = title,
                                    endActions = { Checkbox(if (id in hidden) ToggleableState.Off else ToggleableState.On, null, enabled = !saving) },
                                    enabled = !saving, onClick = {
                                        if (id !in hidden && order.count { it !in hidden } <= 1) {
                                            failure = "至少保留一个显示的标签。"
                                            Toast.makeText(host, failure, Toast.LENGTH_SHORT).show()
                                        } else { hidden = if (id in hidden) hidden - id else hidden + id; failure = "" }
                                    })
                                Action("上移", {
                                    val current = order.indexOf(id)
                                    if (!saving && current > 0) order = order.toMutableList().apply { java.util.Collections.swap(this, current, current - 1) }
                                }, enabled = !saving && index > 0,
                                    modifier = Modifier.semantics { contentDescription = "上移标签“$title”" })
                                Action("下移", {
                                    val current = order.indexOf(id)
                                    if (!saving && current >= 0 && current < order.lastIndex) order = order.toMutableList().apply { java.util.Collections.swap(this, current, current + 1) }
                                }, enabled = !saving && index < order.lastIndex,
                                    modifier = Modifier.semantics { contentDescription = "下移标签“$title”" })
                            }
                        }
                    }
                }
                if (failure.isNotEmpty()) Description(failure, true)
                DialogActions("保存", {
                    if (!saving) {
                        val savedOrder = order.toList(); val savedHidden = hidden.toSet()
                        val accepted = host.tagLayoutTask("正在保存首页标签布局…", Work {
                            try { settings.setHomeTabLayout(savedOrder, savedHidden) }
                            catch (e: RuntimeException) { return@Work Runnable { saving = false; bridge.setDialogDismissible(true); failure = "未保存布局：${error(e)}" } }
                            Runnable { dismiss(); host.homeTabLayoutChanged(); host.tagMessage("已保存标签顺序与显示设置。", false) }
                        })
                        if (accepted) { saving = true; bridge.setDialogDismissible(false); failure = "" }
                        else failure = "正在处理其他操作，完成后再保存。"
                    }
                }, ::dismiss, enabled = !saving)
            }
        } catch (e: RuntimeException) { alert("无法打开排序与隐藏", "首页布局未被修改。请刷新后重试。\n${error(e)}") }
    }

    fun exportTag(id: String) {
        if (host.tagBusy()) return
        try {
            val tag = find(id) ?: return host.tagMessage("此标签已不存在，请刷新后重试。", true)
            requestTagExport(listOf(tag))
        } catch (e: RuntimeException) { host.tagMessage("无法导出标签：${error(e)}", true) }
    }
    fun exportBuiltin(id: String, name: String, packages: Set<String>) {
        if (host.tagBusy()) return
        try {
            val snapshot = packages.toSet()
            requestExport(TabSnapshotCodec.encode(id, name, FreezeTriggers.MANUAL, snapshot), "lightfreeze-tab-snapshot-",
                "已导出“$name”的 ${snapshot.size} 个应用成员快照。导入后创建手动模式的静态标签，可再设置条件。")
        } catch (e: RuntimeException) { host.tagMessage("无法导出成员快照：${error(e)}", true) }
    }
    fun back(): Boolean {
        if (editingMembers) { editingMembers = false; show(); return true }
        if (currentId != null) { currentId = null; show(); return true }
        return false
    }
    fun show() {
        if (editingMembers) return
        try {
            val tag = find(currentId)
            if (tag == null) { currentId = null; showList() } else showDetail(tag)
        } catch (e: RuntimeException) {
            page("标签配置错误") { Description("无法安全读取标签配置：${error(e)}\n配置未被清空，也未按空标签执行。请保留原配置并检查数据。", true) }
        }
    }
    private fun page(title: String, screenId: String = title, content: @Composable ColumnScope.() -> Unit) {
        bridge.page(title, true, host.tagEnterPage(title)) { padding ->
            key(screenId) {
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            }
        }
    }
    @Composable private fun NavigationAction(title: String, icon: Int, layoutOnly: Boolean = false, action: () -> Unit) {
        BasicComponent(
            title = title,
            startAction = { ResourceIcon(icon) },
            endActions = { ResourceIcon(R.drawable.ic_chevron_right) },
            enabled = !LocalUiBusy.current,
            onClick = { if (layoutOnly || !host.tagBusy()) action() },
        )
    }
    private fun showList() {
        val tags = orderedTags(); val mode = settings.defaultMode
        page("标签", "tags") {
            Description("每个应用归属一个标签，所有操作入口采用相同回冻规则。")
            Section("我的标签 · ${tags.size}")
            if (tags.isEmpty()) Description("还没有自定义标签。新建后可直接添加应用，再选择锁屏或划卡回冻。")
            else Card(Modifier.fillMaxWidth()) { tags.forEach { tag ->
                BasicComponent(title = tag.name, summary = "${tag.packages.size} 个应用 · ${modeName(tag.mode)}",
                    startAction = { ResourceIcon(R.drawable.ic_folder) },
                    endActions = { ResourceIcon(R.drawable.ic_chevron_right) },
                    onClick = { if (!host.tagBusy()) openTag(tag.id) })
            } }
            Section("标签管理")
            Card(Modifier.fillMaxWidth()) {
                NavigationAction("新建标签", R.drawable.ic_add, action = ::createTag)
                NavigationAction("首页标签排序与隐藏", R.drawable.ic_sort, layoutOnly = true, action = ::showHomeTabLayout)
                NavigationAction("导入标签", R.drawable.ic_import, action = ::requestImport)
                NavigationAction("导出所有自定义标签", R.drawable.ic_export, action = ::requestExport)
            }
            ModeControl(AppTabs.ALL, mode)
        }
    }
    private fun showDetail(tag: Tag) {
        page(tag.name, "tag:${tag.id}") {
            Description("${tag.packages.size} 个应用")
            Card(Modifier.fillMaxWidth()) {
                NavigationAction("改名", R.drawable.ic_edit) { editName(tag) }
                NavigationAction("添加应用到此标签", R.drawable.ic_add) { loadMembers(tag, true) }
                NavigationAction("编辑成员（仅分类）", R.drawable.ic_apps) { loadMembers(tag, false) }
            }
            Description("其他标签的应用加入后会移到这里。取消成员仍保留管理，使用未分组默认规则。")
            ModeControl(tag.id, tag.mode)
            Section("分组操作")
            Card(Modifier.fillMaxWidth()) {
                NavigationAction("导出此标签", R.drawable.ic_export) { exportTag(tag.id) }
                NavigationAction("冻结标签内应用", R.drawable.ic_freeze) { confirmAction(tag, true) }
                NavigationAction("解冻标签内应用", R.drawable.ic_power) { confirmAction(tag, false) }
            }
            Description("解冻不会批量打开应用。自动回冻仍受全局保护。")
            Section("桌面快捷方式")
            Card(Modifier.fillMaxWidth()) {
                NavigationAction("添加冻结快捷方式", R.drawable.ic_shortcut) { pin("tag", tag.id, tag.name, "freeze") }
                NavigationAction("添加解冻快捷方式", R.drawable.ic_shortcut) { pin("tag", tag.id, tag.name, "unfreeze") }
            }
            Section("URL 调用")
            Card(Modifier.fillMaxWidth()) { TargetLinks("tag", tag.id) }
            Action("删除“${tag.name}”", {
                if (!host.tagBusy()) confirm("删除标签？", "移除此标签及分组链接，原快捷方式不再执行。成员仍保留管理与恢复记录，改用未分组默认规则。本次不冻结或解冻；要退出管理，请使用「移出管理并解冻」。", "删除") {
                    saveTags(settings.tags.filterNot { it.id == tag.id }, "已删除标签。", true)
                }
            }, modifier = Modifier.fillMaxWidth())
        }
    }

    @Composable private fun ModeControl(tabId: String, mode: Int) {
        var saved by remember(tabId, mode) { mutableIntStateOf(mode) }
        var draft by remember(tabId, mode) { mutableIntStateOf(mode) }
        var message by remember(tabId, mode) { mutableStateOf("") }
        val defaultRule = tabId == AppTabs.ALL
        Section(if (defaultRule) "未分组默认规则（可多选）" else "此标签回冻条件（可多选）")
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
            Description(if (defaultRule) "用于未分组的受管应用。满足任一条件即尝试回冻，均不选为手动。" else "成员始终跟随本标签。满足任一条件即尝试回冻，均不选为手动。")
            listOf(FreezeTriggers.LOCK to "锁屏后回冻", FreezeTriggers.CARD to "划卡回冻（移除最近任务卡片）").forEach { (mask, label) ->
                CheckRow(label, FreezeTriggers.has(draft, mask), {
                    if (!host.tagBusy()) { draft = draft xor mask; message = "" }
                })
            }
            Description(if (message.isEmpty()) tabModeDescription(draft) else message)
            Action(if (defaultRule) "保存未分组默认规则" else "保存标签规则", {
                if (!host.tagBusy() && draft != saved) {
                    val requested = draft
                    work("正在保存回冻规则…") {
                        try { if (defaultRule) settings.defaultMode = requested else settings.setTabMode(tabId, requested) }
                        catch (e: RuntimeException) { return@work Runnable { message = "未保存回冻方式：${error(e)}" } }
                        val result = host.tagSyncConfig()
                        Runnable {
                            saved = requested; draft = requested
                            message = tabModeDescription(requested) + if (result.success) "" else "\n本地已保存；系统同步未完成：${result.message}"
                            host.tagMessage(if (defaultRule) "已保存未分组默认规则。" else "已保存所属标签的回冻条件。", !result.success)
                        }
                    }
                }
            }, enabled = draft != saved, primary = true, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        }
    }
    private fun tabModeDescription(mode: Int): String {
        val c = host.tagSystemStatus()
        val supported = mode == 0 || (c.getBoolean("ready") && c.getBoolean("tabSessionsSupported") && c.getBoolean("tagFollowSupported") && c.getBoolean("ownerRulesSupported") &&
            (!FreezeTriggers.has(mode, FreezeTriggers.CARD) || c.getBoolean("multiTriggerSupported")) &&
            (!FreezeTriggers.has(mode, FreezeTriggers.LOCK) || c.getBoolean("lockSupported")) &&
            (!FreezeTriggers.has(mode, FreezeTriggers.CARD) || c.getBoolean("cardSupported")))
        return "当前：${FreezeTriggers.describe(mode)}。" + (if (mode == 0) "" else if (supported) "系统已报告所选触发条件可用。" else "可先保存；自动执行前需更新系统模块并重启。") + "媒体与最近任务保护继续有效。"
    }
    @Composable private fun CheckRow(title: String, checked: Boolean, action: () -> Unit, enabled: Boolean = true) {
        val editable = enabled && !LocalUiBusy.current
        BasicComponent(title = title, enabled = editable, onClick = action,
            endActions = { Checkbox(if (checked) ToggleableState.On else ToggleableState.Off, null, enabled = editable) })
    }
    private fun editName(original: Tag?) {
        bridge.dialog(if (original == null) "新建标签" else "标签改名") {
            var name by remember { mutableStateOf(original?.name ?: "") }
            var failure by remember { mutableStateOf("") }
            SearchField(name, { name = it }, "标签名称（最多 40 个字符）")
            if (failure.isNotEmpty()) Description(failure, true)
            DialogActions("保存", {
                if (!host.tagBusy()) try {
                    val changed = Tag(original?.id ?: UUID.randomUUID().toString(), name, original?.mode ?: FreezeTriggers.MANUAL, original?.packages ?: emptySet())
                    val candidate = TagCodec.upsertSingleMembership(settings.tags, changed)
                    TagCodec.encode(candidate)
                    currentId = changed.id; dismiss()
                    saveTags(candidate, if (original == null) "已新建标签，请添加成员。" else "已修改标签名称。", false)
                } catch (e: IllegalArgumentException) { failure = error(e) }
            }, ::dismiss)
        }
    }
    private fun saveTag(changed: Tag, success: String) {
        try { saveTags(TagCodec.upsertSingleMembership(settings.tags, changed), success, false) }
        catch (e: IllegalArgumentException) { show(); host.tagMessage(error(e), true) }
    }
    private fun saveTags(candidate: List<Tag>, success: String, returnToList: Boolean) {
        work("正在保存标签并同步规则…") {
            try { settings.tags = candidate }
            catch (e: RuntimeException) { return@work Runnable { show(); host.tagMessage("未保存标签配置：${error(e)}", true) } }
            val result = host.tagSyncConfig()
            Runnable {
                editingMembers = false; if (returnToList) currentId = null
                show(); host.tagMessage(success + if (result.success) "\n${result.message}" else "\n本地已保存；系统同步未完成：${result.message}", !result.success)
            }
        }
    }
    private fun loadMembers(tag: Tag, adding: Boolean) {
        work("正在读取可选应用…") {
            val installed = AppRepository(host).load(true)
            Runnable { showMembers(tag, installed, adding) }
        }
    }
    private data class Member(val pkg: String, val label: String, val missing: Boolean, val origin: String)
    private fun showMembers(tag: Tag, installed: List<AppEntry>, adding: Boolean) {
        editingMembers = true
        val origins = linkedMapOf<String, String>()
        settings.tags.forEach { existing -> existing.packages.forEach { origins[it] = if (existing.id == tag.id) "当前标签" else "来自「${existing.name}」，选中后移入" } }
        val managed = settings.allManagedPackages; val seen = hashSetOf<String>()
        val members = installed.filter { it.packageName != "android" && (!adding || it.packageName !in tag.packages) }.mapTo(mutableListOf()) {
            seen.add(it.packageName)
            Member(it.packageName, it.label, false, origins[it.packageName] ?: if (it.packageName in managed) "未分组受管应用" else "尚未纳入管理")
        }
        if (!adding) tag.packages.filter { seen.add(it) }.forEach { members.add(Member(it, it, true, "当前标签")) }
        members.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        val selection = if (adding) MemberSelection.adding(tag.packages) else MemberSelection.editing(tag.packages)
        val title = tag.name + if (adding) " · 添加应用" else " · 编辑成员"
        bridge.page(title, true, host.tagEnterPage(title)) { padding ->
            key(tag.id, adding) {
                var query by remember { mutableStateOf("") }
                var chosen by remember { mutableStateOf<Set<String>>(selection.selected()) }
                val shown = remember(query) { val needle = query.trim().lowercase(Locale.ROOT); members.filter { it.label.lowercase(Locale.ROOT).contains(needle) || it.pkg.lowercase(Locale.ROOT).contains(needle) } }
                Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                    SearchField(query, { query = it })
                    Description("已选 ${chosen.size} 个 · 当前显示 ${shown.size} 个")
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item("member-instructions") {
                            Description(if (adding) "所选应用会加入「${tag.name}」，已有归属会移入。仅添加不执行手动冻结；添加并冻结只处理所选新增项。" else "仅修改分类。取消成员仍保留管理，使用未分组默认规则；未安装成员也可保留。")
                        }
                        item("selection-tools") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Action("全选当前结果", { if (!host.tagBusy()) { selection.selectCurrentResults(shown.map { it.pkg }); chosen = selection.selected() } }, modifier = Modifier.weight(1f))
                                Action("清空选择", { if (!host.tagBusy()) { selection.selectCurrentResults(emptySet()); chosen = selection.selected() } }, modifier = Modifier.weight(1f))
                            }
                        }
                        item("selection-instructions") {
                            Description("搜索保留勾选；全选会替换此前选择，只选当前显示项。")
                        }
                        if (shown.isEmpty()) item("empty-results") { Description("没有匹配的应用") }
                        items(shown, key = { it.pkg }) { member ->
                            BasicComponent(title = member.label, summary = (if (member.missing) "未安装 · " else "") + member.pkg + "\n" + member.origin,
                                startAction = { AppIcon(host, member.pkg) },
                                endActions = { Checkbox(if (member.pkg in chosen) ToggleableState.On else ToggleableState.Off, null) },
                                onClick = {
                                    if (!host.tagBusy()) { selection.setChecked(member.pkg, member.pkg !in chosen); chosen = selection.selected() }
                                })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Action("取消", { if (!host.tagBusy()) { editingMembers = false; show() } }, modifier = Modifier.weight(1f))
                        if (adding) {
                            Action("仅添加", { if (!host.tagBusy()) addSelectedMembers(tag.id, chosen, false) }, modifier = Modifier.weight(1f))
                            Action("添加并冻结", { if (!host.tagBusy()) addSelectedMembers(tag.id, chosen, true) }, primary = true, modifier = Modifier.weight(1f))
                        } else Action("保存成员", {
                            if (!host.tagBusy()) {
                                val current = find(tag.id)
                                if (current == null) { editingMembers = false; currentId = null; show() }
                                else try { saveTag(Tag(current.id, current.name, current.mode, selection.membersAfter(current.packages)), "已保存 ${selection.size()} 个成员的分类。") }
                                catch (e: IllegalArgumentException) { host.tagMessage(error(e), true) }
                            }
                        }, primary = true, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
    private fun addSelectedMembers(id: String, selected: Set<String>, freeze: Boolean) {
        if (selected.isEmpty()) return host.tagMessage("请先选择要添加的应用。", true)
        val fixed = selected.toSet()
        work(if (freeze) "正在添加所选应用并冻结新增项…" else "正在添加所选应用…") {
            val additions: Set<String>
            try {
                synchronized(SettingsStore::class.java) {
                    val current = find(id) ?: throw IllegalArgumentException("目标标签已删除，请返回重新选择。")
                    val selection = MemberSelection.adding(current.packages)
                    fixed.forEach { selection.setChecked(it, true) }
                    additions = selection.newlyAdded(current.packages)
                    if (additions.isNotEmpty()) settings.tags = TagCodec.addMembers(settings.tags, id, additions)
                }
            } catch (e: RuntimeException) { return@work Runnable { host.tagMessage("未添加应用：${error(e)}", true) } }
            val synced = host.tagSyncConfig()
            val frozen = if (freeze) freezeSelectedAdditions(additions) else null
            val result = "已添加 ${additions.size} 个应用；其他标签中的所选成员已移入。" +
                (frozen?.let { "\n${it.message}" } ?: "") + if (synced.success) "" else "\n本地分类已保存；系统同步未完成：${synced.message}"
            Runnable { editingMembers = false; host.tagRefresh(result, !synced.success || frozen?.success == false) }
        }
    }
    private fun freezeSelectedAdditions(packages: Set<String>): OperationResult {
        if (packages.isEmpty()) return OperationResult(true, "没有新增应用，未执行冻结。")
        val apps = AppRepository(host).loadPackages(packages)
        val results = FreezerController(host, settings).changeAll(apps, true, false)
        val outcomes = apps.indices.associate { apps[it].packageName to results[it] }
        var success = 0
        val detail = packages.sorted().joinToString("\n") { pkg ->
            val result = outcomes[pkg]; if (result?.success == true) success++
            "$pkg：${result?.message ?: "当前用户未安装，未冻结"}"
        }
        return OperationResult(success == packages.size, "仅处理 ${packages.size} 个所选新增项：冻结成功 $success 个，失败 ${packages.size - success} 个。\n$detail")
    }
    private fun confirmAction(tag: Tag, freeze: Boolean) {
        confirm((if (freeze) "冻结“" else "解冻“") + tag.name + "”？", "${tag.packages.size} 个成员。" + if (freeze) "冻结可能中断播放、通话、下载等任务。" else "解冻并按所属标签规则回冻，不启动应用。", "执行") {
            work("正在处理标签内应用…") {
                val result = targets.execute(if (freeze) "freeze" else "unfreeze", "tag", tag.id)
                Runnable { alert("标签操作结果", result.message); host.tagRefresh() }
            }
        }
    }
    private fun pin(type: String, id: String, name: String, operation: String) {
        val manager = host.getSystemService(ShortcutManager::class.java)
        if (manager == null || !manager.isRequestPinShortcutSupported) return host.tagMessage("当前桌面不支持添加固定快捷方式。", true)
        work("正在准备标签快捷方式…") {
            val intent = if (type == "tab") links.tabShortcutIntent(operation, id) else links.tagShortcutIntent(operation, id)
            Runnable {
                val verb = if (operation == "freeze") "冻结" else "解冻"
                val info = ShortcutInfo.Builder(host, "$type:$id:$operation").setShortLabel("$verb · $name").setLongLabel("${verb}标签“$name”")
                    .setIcon(Icon.createWithResource(host, R.drawable.ic_launcher)).setIntent(intent).build()
                try { val accepted = manager.requestPinShortcut(info, null); host.tagMessage(if (accepted) "已请求添加，请在桌面确认。" else "桌面未接受快捷方式请求。", !accepted) }
                catch (e: RuntimeException) { host.tagMessage("无法添加快捷方式：${error(e)}", true) }
            }
        }
    }

    @Composable fun UrlSettings() {
        var enabled by remember { mutableStateOf(links.isEnabled) }
        ToggleRow("允许 URL 调用", "持有有效链接的工具可操作指定目标；桌面快捷方式独立使用。", enabled, { checked ->
            if (!host.tagBusy() && checked != links.isEnabled) work("正在保存 URL 调用设置…") {
                try { links.setEnabled(checked) }
                catch (e: RuntimeException) { return@work Runnable { enabled = links.isEnabled; host.tagMessage("未保存 URL 设置：${error(e)}", true) } }
                Runnable { enabled = checked; host.tagMessage(if (checked) "已允许带有效令牌的 URL 调用。" else "已关闭 URL 调用。", false) }
            }
        })
        Description("链接包含授权令牌，请只交给可信工具；可在目标详情撤销。")
    }
    @Composable fun TargetLinks(type: String, id: String) {
        var message by remember(type, id) { mutableStateOf(if (links.isEnabled) "URL 调用已开启。持有链接即可操作此目标。" else "URL 调用当前关闭，可先复制链接再在设置中开启。") }
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Description(message)
            Action("复制冻结 URL", { if (!host.tagBusy()) copyUrl("freeze", type, id) { message = it } }, modifier = Modifier.fillMaxWidth())
            Action("复制解冻 URL", { if (!host.tagBusy()) copyUrl("unfreeze", type, id) { message = it } }, modifier = Modifier.fillMaxWidth())
            Action("撤销此目标的旧 URL", {
                if (!host.tagBusy()) confirm("撤销旧链接？", "此目标已复制的冻结、解冻 URL 将全部失效。可重新复制新链接，桌面快捷方式不受影响。", "撤销") {
                    work("正在撤销 URL 授权…") {
                        try { links.revokeUrlToken(type, id); Runnable { host.tagMessage("旧 URL 已撤销。重新复制会生成新授权链接。", false) } }
                        catch (e: RuntimeException) { Runnable { host.tagMessage("撤销未完成：${error(e)}", true) } }
                    }
                }
            }, modifier = Modifier.fillMaxWidth())
        }
    }
    private fun copyUrl(operation: String, type: String, id: String, state: (String) -> Unit) {
        work("正在生成目标链接…") {
            val url = try { links.url(operation, type, id) }
            catch (e: RuntimeException) { return@work Runnable { state("未生成链接：${error(e)}") } }
            Runnable {
                val clip = ClipData.newPlainText("轻冻操作链接", url)
                clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                host.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                state("已复制${if (operation == "freeze") "冻结" else "解冻"} URL。" + if (links.isEnabled) "" else "需在设置中开启 URL 调用。")
            }
        }
    }

    fun requestImport() {
        if (host.tagBusy()) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        try { host.startActivityForResult(intent, IMPORT) }
        catch (e: RuntimeException) { host.tagMessage("无法打开文件选择器：${error(e)}", true) }
    }
    private fun requestExport() {
        if (host.tagBusy()) return
        try { requestTagExport(settings.tags) }
        catch (e: RuntimeException) { host.tagMessage("无法导出标签：${error(e)}", true) }
    }
    private fun requestTagExport(tags: List<Tag>) = requestExport(TagCodec.encode(tags), "lightfreeze-tags-", "已导出 ${tags.size} 个标签、${memberCount(tags)} 个应用的配置。")
    private fun requestExport(json: String, prefix: String, summary: String) {
        if (pendingExportId != null) return host.tagMessage("请先完成或取消当前导出，再重试。", false)
        val id = UUID.randomUUID().toString()
        val filename = prefix + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()) + ".json"
        pendingExportId = id
        host.tagDocumentTask("正在准备导出…", Work {
            try { ExportDraft(json, filename, summary).save(exportDirectory, id) }
            catch (e: Exception) { return@Work Runnable { pendingExportId = null; exportPickerLaunched = false; host.tagMessage("无法准备导出：${error(e)}", true) } }
            Runnable {
                try { exportPickerLaunched = true; host.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE, filename), EXPORT) }
                catch (e: RuntimeException) { clearPendingExport(); host.tagMessage("无法打开文件选择器：${error(e)}", true) }
            }
        })
    }
    private fun clearPendingExport() {
        val id = pendingExportId; pendingExportId = null; exportPickerLaunched = false
        if (id != null) host.tagDocumentTask("", Work { ExportDraft.remove(exportDirectory, id); Runnable {} })
    }
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != IMPORT && requestCode != EXPORT) return
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) { if (requestCode == EXPORT) clearPendingExport(); return }
        if (requestCode == IMPORT) work("正在读取并验证标签文件…") {
            val text = host.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法读取所选文件" }
                val bytes = ByteArrayOutputStream(); val buffer = ByteArray(8192); var total = 0
                while (true) {
                    val count = input.read(buffer); if (count == -1) break
                    total += count
                    require(total <= TagCodec.MAX_BYTES) { "标签文件超过 1 MiB；导入配置合计最多包含 500 个应用，请缩小文件后重试" }
                    bytes.write(buffer, 0, count)
                }
                try { StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString() }
                catch (e: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("标签文件必须为有效 UTF-8 文本", e) }
            }
            val incoming = TabSnapshotCodec.decodeForImport(text); settings.validateTags(incoming)
            val existing = settings.tags
            var mergeError: String? = null
            val merged = try { TagCodec.merge(existing, incoming).also { settings.validateTags(it) } }
                catch (e: IllegalArgumentException) { mergeError = error(e); null }
            Runnable { previewImport(existing, incoming, merged, mergeError) }
        } else {
            if (!exportPickerLaunched) return
            exportPickerLaunched = false // Consume final picker result before asynchronous writing.
            val id = pendingExportId
            if (id == null) return alert("请重试导出", "导出草稿不可用，请返回标签重新导出。")
            // The private snapshot survives recreation; export does not need a privileged main claim.
            host.tagDocumentTask("正在导出标签…", Work {
                try {
                    val draft = ExportDraft.load(exportDirectory, id)
                    host.contentResolver.openOutputStream(uri, "wt").use { output ->
                        requireNotNull(output) { "无法写入所选文件" }
                        output.write(draft.json.toByteArray(StandardCharsets.UTF_8)); output.flush()
                    }
                    ExportDraft.remove(exportDirectory, id)
                    Runnable { pendingExportId = null; exportPickerLaunched = false; host.tagMessage(draft.summary, false) }
                } catch (e: Exception) {
                    try { ExportDraft.remove(exportDirectory, id) } catch (_: Exception) {}
                    Runnable { pendingExportId = null; exportPickerLaunched = false; host.tagMessage("导出未完成，目标文件可能为空或不完整，请重新导出：${error(e)}", true) }
                }
            })
        }
    }
    private fun previewImport(existing: List<Tag>, incoming: List<Tag>, merged: List<Tag>?, mergeError: String?) {
        bridge.dialog("导入预览") {
            var replace by remember { mutableStateOf(false) }
            Column {
                Description("文件已验证：${incoming.size} 个标签、${memberCount(incoming)} 个应用。导入只修改标签配置，不直接冻结或解冻，也不导入 URL 或桌面授权。")
                Description("内置视图的成员快照会作为自定义静态分组导入，不改变四个动态视图。")
                CheckRow("合并（同 ID 更新，新 ID 新增）", !replace, { replace = false })
                CheckRow("替换全部标签", replace, { replace = true })
                val summary = if (replace) "将替换当前 ${existing.size} 个标签，保留文件中的 ${incoming.size} 个。未保留的标签快捷方式和 URL 将失效。"
                    else if (merged == null) "无法合并：$mergeError。请修改文件，或明确选择替换。"
                    else { val ids = existing.map { it.id }.toSet(); val updates = incoming.count { it.id in ids }; "更新 $updates 个，新增 ${incoming.size - updates} 个；共 ${merged.size} 个标签、${memberCount(merged)} 个应用。" }
                Description(summary, !replace && merged == null)
                incoming.forEach { Description("${it.name} · ${it.packages.size} 个应用 · ${modeName(it.mode)}") }
            }
            DialogActions("导入", {
                if (!host.tagBusy()) {
                    val replaceAll = replace
                    dismiss()
                    work("正在导入标签并同步规则…") {
                        // Re-read merge input at commit time; replacing is an explicit choice.
                        val candidate = if (replaceAll) incoming else TagCodec.merge(settings.tags, incoming)
                        settings.tags = candidate
                        val result = host.tagSyncConfig()
                        Runnable {
                            currentId = null; editingMembers = false; show()
                            host.tagMessage("已导入 ${incoming.size} 个标签，现有 ${candidate.size} 个标签、${memberCount(candidate)} 个应用。" +
                                if (result.success) "\n${result.message}" else "\n本地已保存；系统同步未完成：${result.message}", !result.success)
                        }
                    }
                }
            }, ::dismiss, enabled = replace || merged != null)
        }
    }
    private fun modeName(mode: Int) = if (mode < 0) "保存时确定具体方式" else FreezeTriggers.describe(mode)
    private fun memberCount(tags: List<Tag>) = tags.flatMap { it.packages }.toSet().size
    companion object { private const val IMPORT = 7011; private const val EXPORT = 7012 }
}
