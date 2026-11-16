package io.github.yudigaga.lightfreeze.ui

import android.view.WindowInsets
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.yudigaga.lightfreeze.R
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

internal val LocalUiBusy = staticCompositionLocalOf { false }

/** One foreground composition; URL activities and the system process never create this shell. */
class MiuixBridge(private val host: MainActivity) {
    private data class Page(
        val title: String,
        val back: Boolean,
        val destination: Int,
        val content: @Composable (PaddingValues) -> Unit,
    )

    private data class Dialog(
        val id: Long,
        val title: String,
        val onDismiss: () -> Unit,
        val content: @Composable ColumnScope.() -> Unit,
    )

    private var currentPage by mutableStateOf<Page?>(null)
    private var currentDialog by mutableStateOf<Dialog?>(null)
    private var taskBusy by mutableStateOf(false)
    private var feedbackMessage by mutableStateOf("")
    private var failure by mutableStateOf(false)
    private var canDismissDialog by mutableStateOf(true)
    private var nextDialogId = 0L
    private val composeView = ComposeView(host).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { Shell() }
    }

    fun view(): ComposeView = composeView

    fun page(title: String, back: Boolean, destination: Int, body: @Composable (PaddingValues) -> Unit) {
        currentPage = Page(title, back, destination, body)
    }

    fun setBusy(value: Boolean) { taskBusy = value }

    @JvmOverloads
    fun setMessage(value: String, failure: Boolean = false) {
        feedbackMessage = value
        this.failure = failure
    }

    fun dialog(
        title: String,
        onDismiss: () -> Unit = {},
        content: @Composable ColumnScope.() -> Unit,
    ) {
        if (currentDialog != null) hideKeyboard()
        canDismissDialog = true
        currentDialog = Dialog(++nextDialogId, title, onDismiss, content)
    }

    fun setDialogDismissible(value: Boolean) { canDismissDialog = value }

    /** Explicit completion is allowed to close a dialog after its authorized write. */
    fun dismissDialog() {
        currentDialog = null
        canDismissDialog = true
        hideKeyboard()
    }

    private fun hideKeyboard() { host.window.insetsController?.hide(WindowInsets.Type.ime()) }

    fun handleBack(): Boolean {
        val dialog = currentDialog ?: return false
        if (!taskBusy && canDismissDialog) {
            dismissDialog()
            dialog.onDismiss()
        }
        return true
    }

    @Composable
    private fun Shell() {
        val theme = remember { ThemeController(ColorSchemeMode.MonetSystem) }
        MiuixTheme(controller = theme) {
            CompositionLocalProvider(LocalUiBusy provides taskBusy) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = maxWidth >= 600.dp
                    val compactHeight = maxHeight < 500.dp
                    val dialogMaxHeight = maxHeight * 0.72f
                    val feedbackMaxHeight = minOf(maxHeight * 0.25f, 160.dp)
                    val page = currentPage
                    val entries = listOf(
                        Triple(1101, "首页", R.drawable.ic_home),
                        Triple(1102, "应用", R.drawable.ic_apps),
                        Triple(1103, "设置", R.drawable.ic_settings),
                        Triple(1104, "关于", R.drawable.ic_info),
                    )
                    Row(Modifier.fillMaxSize()) {
                        if (wide) {
                            NavigationRail {
                                entries.forEach { (id, label, icon) ->
                                    NavigationRailItem(
                                        selected = page?.destination == id,
                                        onClick = { host.uiNavigate(id) },
                                        icon = ImageVector.vectorResource(icon),
                                        label = label,
                                        enabled = !taskBusy && currentDialog == null,
                                    )
                                }
                            }
                        }
                        Scaffold(
                            modifier = Modifier.weight(1f),
                            topBar = {
                                val backIcon: @Composable () -> Unit = {
                                    if (page?.back == true) IconButton(
                                        onClick = { if (!handleBack()) host.uiBack() },
                                        enabled = !taskBusy,
                                    ) { MiuixUi.ResourceIcon(R.drawable.ic_back, "返回") }
                                }
                                if (compactHeight) SmallTopAppBar(title = page?.title ?: "轻冻", navigationIcon = backIcon)
                                else TopAppBar(title = page?.title ?: "轻冻", navigationIcon = backIcon)
                            },
                            bottomBar = {
                                if (!wide) NavigationBar {
                                    entries.forEach { (id, label, icon) ->
                                        NavigationBarItem(
                                            selected = page?.destination == id,
                                            onClick = { host.uiNavigate(id) },
                                            icon = ImageVector.vectorResource(icon),
                                            label = label,
                                            enabled = !taskBusy && currentDialog == null,
                                        )
                                    }
                                }
                            },
                        ) { insets ->
                            Column(Modifier.fillMaxSize().padding(insets)) {
                                if (taskBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                                if (feedbackMessage.isNotBlank()) {
                                    val feedbackScroll = rememberScrollState()
                                    LaunchedEffect(feedbackMessage) { feedbackScroll.scrollTo(0) }
                                    Box(Modifier.fillMaxWidth()
                                        .heightIn(max = feedbackMaxHeight)
                                        .verticalScroll(feedbackScroll)
                                        .padding(horizontal = 20.dp, vertical = 8.dp)
                                        .semantics { liveRegion = LiveRegionMode.Polite }) {
                                        MiuixUi.Description(feedbackMessage, failure)
                                    }
                                }
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    page?.content?.invoke(PaddingValues(0.dp))
                                }
                            }
                            val dialog = currentDialog
                            BackHandler(enabled = dialog != null) { handleBack() }
                            if (dialog != null) key(dialog.id) {
                                OverlayDialog(
                                    show = true,
                                    title = dialog.title,
                                    onDismissRequest = { if (currentDialog?.id == dialog.id) handleBack() },
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth()
                                            .heightIn(max = dialogMaxHeight)
                                            .verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                        content = dialog.content,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
