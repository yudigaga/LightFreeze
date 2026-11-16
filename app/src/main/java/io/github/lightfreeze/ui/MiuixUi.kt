package io.github.yudigaga.lightfreeze.ui

import android.view.View
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.yudigaga.lightfreeze.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Small repeated presentation pieces; policy and mutations remain in the Java controllers. */
object MiuixUi {
    @Composable
    fun Description(text: String, error: Boolean = false) {
        Text(
            text = text,
            color = if (error) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2,
        )
    }

    @Composable
    fun Section(title: String) {
        Text(
            text = title,
            modifier = Modifier.padding(start = 12.dp, top = 20.dp, bottom = 8.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.subtitle,
        )
    }

    @Composable
    fun Action(
        text: String,
        onClick: () -> Unit,
        enabled: Boolean = true,
        primary: Boolean = false,
        modifier: Modifier = Modifier,
    ) {
        Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = 48.dp),
            enabled = enabled && !LocalUiBusy.current,
            colors = if (primary) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
        ) { Text(text) }
    }

    @Composable
    fun DialogActions(
        confirmText: String,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit,
        enabled: Boolean = true,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Action("取消", onDismiss, modifier = Modifier.weight(1f))
            Action(confirmText, onConfirm, enabled, primary = true, modifier = Modifier.weight(1f))
        }
    }

    @Composable
    fun ToggleRow(
        title: String,
        summary: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        enabled: Boolean = true,
    ) {
        val editable = enabled && !LocalUiBusy.current
        BasicComponent(
            title = title,
            summary = summary,
            enabled = editable,
            onClick = { if (editable) onCheckedChange(!checked) },
            endActions = { Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = editable,
                modifier = Modifier.semantics { contentDescription = title },
            ) },
        )
    }

    @Composable
    fun ResourceIcon(@DrawableRes resId: Int, description: String? = null, size: Dp = 24.dp) {
        Icon(ImageVector.vectorResource(resId), description, Modifier.size(size))
    }

    @Composable
    fun AppIcon(host: MainActivity, packageName: String, size: Dp = 40.dp) {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            },
            modifier = Modifier.size(size),
            update = { image ->
                if (image.tag != packageName) {
                    image.tag = packageName
                    image.setImageResource(R.drawable.ic_apps)
                    host.tagLoadIcon(packageName, image)
                }
            },
        )
    }

    @Composable
    fun SearchField(
        query: String,
        onQueryChange: (String) -> Unit,
        label: String = "搜索应用名称或包名",
        enabled: Boolean = true,
    ) {
        val keyboard = LocalSoftwareKeyboardController.current
        InputField(
            query = query,
            onQueryChange = onQueryChange,
            onSearch = { keyboard?.hide() },
            expanded = false,
            onExpandedChange = {},
            label = label,
            enabled = enabled && !LocalUiBusy.current,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
