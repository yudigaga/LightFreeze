package io.github.yudigaga.lightfreeze.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.github.yudigaga.lightfreeze.R
import io.github.yudigaga.lightfreeze.control.AppEntry
import io.github.yudigaga.lightfreeze.control.AppRepository
import io.github.yudigaga.lightfreeze.control.FreezerController
import io.github.yudigaga.lightfreeze.control.OperationResult
import io.github.yudigaga.lightfreeze.core.SettingsStore
import java.text.Collator
import java.util.Locale
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Checkbox

/** Selection is a local draft until the existing verified settings transaction is saved. */
class HighRiskUi(
    private val host: MainActivity,
    private val settings: SettingsStore,
    private val repository: AppRepository,
    private val controller: FreezerController,
) {
    private data class Choice(val packageName: String, val label: String, val reason: String)

    @Composable
    fun Settings(cached: List<AppEntry>) {
        val allowed = settings.highRiskAllowed.sorted()
        val labels = cached.associate { it.packageName to it.label }
        MiuixUi.Description("冻结关键组件可能影响系统界面、桌面和授权通道，风险由用户自行承担。放行仅解除轻冻保护，系统仍可能拒绝冻结。")
        BasicComponent(
            title = "管理高危放行列表",
            summary = if (allowed.isEmpty()) "未放行任何应用" else "已放行 ${allowed.size} 个应用",
            startAction = { MiuixUi.ResourceIcon(R.drawable.ic_shield) },
            endActions = { MiuixUi.ResourceIcon(R.drawable.ic_chevron_right) },
            onClick = { open() },
            enabled = !LocalUiBusy.current,
        )
        allowed.forEach { pkg ->
            BasicComponent(
                title = labels[pkg] ?: "当前用户未安装",
                summary = pkg,
                startAction = { MiuixUi.ResourceIcon(R.drawable.ic_check) },
                onClick = { open() },
                enabled = !LocalUiBusy.current,
            )
        }
    }

    fun open() {
        if (host.tagBusy()) {
            host.tagMessage("请先完成当前操作并确认本体解冻状态。", true)
            return
        }
        host.tagTask("正在读取高危应用与放行列表…") {
            val allowed = settings.highRiskAllowed.toSet()
            val candidates = linkedMapOf<String, Choice>()
            repository.load(true).forEach { app ->
                if (!excluded(app.packageName)) {
                    val reason = controller.highRiskReason(app)
                    if (reason != null || app.packageName in allowed) {
                        candidates[app.packageName] = Choice(app.packageName, app.label,
                            reason ?: "当前未命中保护项；可取消已有放行。")
                    }
                }
            }
            allowed.forEach { pkg ->
                if (!excluded(pkg) && pkg !in candidates) {
                    candidates[pkg] = Choice(pkg, "当前用户未安装", "取消勾选可移除此放行项。")
                }
            }
            val collator = Collator.getInstance()
            val choices = candidates.values.sortedWith { a, b ->
                collator.compare(a.label, b.label).takeIf { it != 0 } ?: a.packageName.compareTo(b.packageName)
            }
            Runnable { choose(choices, allowed) }
        }
    }

    private fun excluded(pkg: String) = pkg == "android" || pkg == host.packageName

    private fun choose(choices: List<Choice>, allowed: Set<String>) {
        host.miuix().dialog("高危放行列表") {
            var draft by remember { mutableStateOf(allowed) }
            var query by remember { mutableStateOf("") }
            var failure by remember { mutableStateOf("") }
            var saving by remember { mutableStateOf(false) }
            val needle = query.trim().lowercase(Locale.ROOT)
            val visible = remember(choices, needle) {
                choices.filter { needle.isEmpty() || it.label.lowercase(Locale.ROOT).contains(needle)
                    || it.packageName.lowercase(Locale.ROOT).contains(needle) }
            }
            val editable = !saving && !LocalUiBusy.current
            MiuixUi.Description("风险由用户自行承担", error = true)
            MiuixUi.Description("仅勾选需要放行的应用。保存不直接冻结，已有标签规则仍可生效；取消不修改列表。")
            if (failure.isNotBlank()) MiuixUi.Description(failure, error = true)
            MiuixUi.SearchField(query, { query = it }, enabled = editable)
            MiuixUi.Description("已选 ${draft.size} 个 · 显示 ${visible.size} / ${choices.size}")
            if (visible.isEmpty()) MiuixUi.Description("没有匹配的高危应用。可尝试应用名称或完整包名。")
            LazyColumn(Modifier.fillMaxWidth().heightIn(min = 64.dp, max = 280.dp)) {
                items(visible, key = { it.packageName }) { choice ->
                    val selected = choice.packageName in draft
                    val toggle = {
                        if (editable && !host.tagBusy()) {
                            draft = if (selected) draft - choice.packageName else draft + choice.packageName
                            failure = ""
                        }
                    }
                    BasicComponent(
                        title = choice.label,
                        summary = "${choice.packageName}\n${choice.reason}",
                        startAction = { MiuixUi.AppIcon(host, choice.packageName) },
                        endActions = { Checkbox(
                            state = if (selected) ToggleableState.On else ToggleableState.Off,
                            onClick = toggle,
                            enabled = editable,
                            modifier = Modifier.semantics {
                                contentDescription = "${choice.label}，${choice.packageName}，${choice.reason}"
                            },
                        ) },
                        onClick = toggle,
                        enabled = editable,
                    )
                }
            }
            MiuixUi.DialogActions(
                confirmText = "保存放行",
                enabled = editable,
                onDismiss = { host.miuix().dismissDialog() },
                onConfirm = {
                    if (!saving && !host.tagBusy()) {
                        val requested = draft.toSet()
                        host.tagTask("正在保存高危放行并同步规则…") {
                            try { settings.setHighRiskAllowed(requested) }
                            catch (error: RuntimeException) {
                                return@tagTask Runnable {
                                    saving = false
                                    failure = "未保存放行列表：${message(error)}"
                                }
                            }
                            val result = try { host.tagSyncConfig() }
                            catch (error: RuntimeException) { OperationResult(false, message(error)) }
                            Runnable {
                                host.miuix().dismissDialog()
                                host.tagRefresh("已保存本地高危放行列表，共 ${requested.size} 项。\n" +
                                    if (result.success) result.message else "系统同步未完成：${result.message}", !result.success)
                            }
                        }
                        if (host.tagTaskRunning()) saving = true
                    }
                },
            )
        }
    }

    private fun message(error: Exception) = error.message ?: error.javaClass.simpleName
}
