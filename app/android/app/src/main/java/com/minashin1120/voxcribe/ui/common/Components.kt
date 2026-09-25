package com.minashin1120.voxcribe.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.minashin1120.voxcribe.ui.theme.AppTheme
import com.minashin1120.voxcribe.ui.theme.Bs
import com.minashin1120.voxcribe.ui.theme.T
import kotlinx.coroutines.delay

// ---------------- Card ----------------

/** .card: 背景・枠線・角丸・影（gamingは発光枠、retro/stylishはオフセット影） */
fun Modifier.appCard(t: AppTheme, radius: Dp = t.radius, border: Color = t.cardBorder, bg: Color = t.cardBg): Modifier {
    val shape = RoundedCornerShape(radius)
    var m = this
    if (t.hardShadowOffset > 0.dp) {
        m = m.drawBehind {
            val o = t.hardShadowOffset.toPx()
            drawRoundRect(t.hardShadowColor, topLeft = Offset(o, o), size = size, cornerRadius = CornerRadius(radius.toPx()))
        }
    } else if (t.shadowElevation > 0.dp) {
        m = m.shadow(t.shadowElevation, shape, ambientColor = Color(0x14212D4B), spotColor = Color(0x14212D4B))
    } else if (t.isGaming) {
        m = m.shadow(8.dp, shape, ambientColor = Color(0xFFFF00FF), spotColor = Color(0xFFFF00FF))
    }
    val bw = if (t.isGaming) 2.dp else 1.dp
    return m.clip(shape).background(bg).border(bw, border, shape)
}

@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val t = T.c
    Column(modifier.fillMaxWidth().appCard(t), content = content)
}

@Composable
fun CardHeader(modifier: Modifier = Modifier, bg: Color = T.c.cardBg, content: @Composable RowScope.() -> Unit) {
    val t = T.c
    Column {
        Row(
            modifier.fillMaxWidth().background(bg).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
        Box(Modifier.fillMaxWidth().size(width = 0.dp, height = 1.dp).background(t.cardBorder))
    }
}

// ---------------- Section title ----------------

@Composable
fun SectionTitle(icon: ImageVector, eyebrow: String, title: String, small: Boolean = false) {
    val t = T.c
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(t.softPrimary),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = t.primary, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(eyebrow, color = t.primary, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
            Text(
                title, color = t.text, fontSize = if (small) 16.sp else 19.sp,
                fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp
            )
        }
    }
}

// ---------------- Buttons (Bootstrap) ----------------

enum class BtnVariant {
    PRIMARY, OUTLINE_PRIMARY, DANGER, OUTLINE_DANGER, WARNING, OUTLINE_WARNING, SECONDARY, OUTLINE_SECONDARY,
    OUTLINE_DARK, SUCCESS, OUTLINE_SUCCESS, INFO, LIGHT, LINK
}

enum class BtnSize { SM, MD, LG }

@Composable
fun BsButton(
    text: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: BtnVariant = BtnVariant.PRIMARY,
    size: BtnSize = BtnSize.MD,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    pill: Boolean = false,
    borderless: Boolean = false,
    loading: Boolean = false,
    fillWidth: Boolean = false,
) {
    val t = T.c
    val (solid, color) = when (variant) {
        BtnVariant.PRIMARY -> true to t.primary
        BtnVariant.OUTLINE_PRIMARY -> false to t.primary
        BtnVariant.DANGER -> true to Bs.danger
        BtnVariant.OUTLINE_DANGER -> false to Bs.danger
        BtnVariant.WARNING -> true to Bs.warning
        BtnVariant.OUTLINE_WARNING -> false to Bs.warning
        BtnVariant.SECONDARY -> true to Bs.secondary
        BtnVariant.OUTLINE_SECONDARY -> false to Bs.secondary
        BtnVariant.OUTLINE_DARK -> false to (if (t.isDark) Color.White else Bs.dark)
        BtnVariant.SUCCESS -> true to Bs.success
        BtnVariant.OUTLINE_SUCCESS -> false to Bs.success
        BtnVariant.INFO -> true to t.primary
        BtnVariant.LIGHT -> true to Bs.light
        BtnVariant.LINK -> false to t.primary
    }
    val fg = when {
        !solid -> color
        variant == BtnVariant.WARNING || variant == BtnVariant.LIGHT -> Color.Black
        variant == BtnVariant.PRIMARY && (t.key == "gaming" || t.key == "electronic") -> Color.Black
        else -> Color.White
    }
    val radius = if (pill) 999.dp else when (size) {
        BtnSize.SM -> minOf(t.radius, 8.dp)
        BtnSize.MD -> minOf(t.radius, 12.dp)
        BtnSize.LG -> minOf(t.radius, 14.dp)
    }
    val shape = RoundedCornerShape(radius)
    val pad = when (size) {
        BtnSize.SM -> PaddingValues(horizontal = 10.dp, vertical = 5.dp)
        BtnSize.MD -> PaddingValues(horizontal = 14.dp, vertical = 8.dp)
        BtnSize.LG -> PaddingValues(horizontal = 22.dp, vertical = 11.dp)
    }
    val fs = when (size) { BtnSize.SM -> 13.sp; BtnSize.MD -> 15.sp; BtnSize.LG -> 17.sp }
    var m = modifier.alpha(if (enabled) 1f else 0.65f)
    if (solid && variant == BtnVariant.PRIMARY && t.isGaming) {
        val tr = rememberInfiniteTransition(label = "rgb")
        val glow by tr.animateColor(
            Color.Red, Color.Magenta,
            infiniteRepeatable(keyframes<Color> {
                durationMillis = 5000
                Color.Red at 0; Color.Yellow at 1000; Color.Green at 2000; Color.Cyan at 3000; Color.Blue at 4000; Color.Magenta at 5000
            }, RepeatMode.Reverse), label = "rgbc"
        )
        m = m.shadow(10.dp, shape, ambientColor = glow, spotColor = glow)
    } else if (solid && variant == BtnVariant.PRIMARY && enabled) {
        m = m.shadow(6.dp, shape, ambientColor = t.primary.copy(alpha = .3f), spotColor = t.primary.copy(alpha = .3f))
    }
    m = m.clip(shape)
    if (solid) m = m.background(color)
    if (!borderless && variant != BtnVariant.LINK) m = m.border(if (t.isGaming && solid) 2.dp else 1.dp, color, shape)
    m = m.clickable(enabled = enabled && !loading, onClick = onClick)
    if (fillWidth) m = m.fillMaxWidth()
    Row(
        m.padding(pad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (loading) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), color = fg, strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
        } else if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(if (size == BtnSize.LG) 20.dp else 16.dp))
            if (text != null) Spacer(Modifier.width(5.dp))
        }
        if (text != null) {
            Text(text, color = fg, fontSize = fs, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

// ---------------- Badge ----------------

enum class BadgeColor(val bg: Color, val fg: Color) {
    PRIMARY(Color(0xFF0D6EFD), Color.White),
    SECONDARY(Bs.secondary, Color.White),
    SUCCESS(Bs.success, Color.White),
    WARNING(Bs.warning, Color.Black),
    INFO(Bs.info, Color.Black),
    DARK(Bs.dark, Color.White),
    DANGER(Bs.danger, Color.White),
}

data class BadgeSpec(val text: String, val color: BadgeColor)

@Composable
fun Badge(spec: BadgeSpec) {
    Text(
        spec.text,
        color = spec.color.fg,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(spec.color.bg).padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

// ---------------- Switch / Checkbox ----------------

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val t = T.c
    Row(
        Modifier.clickable(enabled = enabled) { onChange(!checked) }.padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            modifier = Modifier.size(width = 44.dp, height = 26.dp),
            colors = SwitchDefaults.colors(
                checkedTrackColor = t.primary, checkedThumbColor = if (t.isDark) Color.Black else Color.White,
                uncheckedTrackColor = t.inputBg, uncheckedBorderColor = t.inputBorder, uncheckedThumbColor = Bs.secondary
            )
        )
        Spacer(Modifier.width(6.dp))
        Text(label, color = t.text, fontSize = 13.sp)
    }
}

@Composable
fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val t = T.c
    Row(Modifier.clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(18.dp).clip(RoundedCornerShape(4.dp))
                .background(if (checked) t.primary else t.inputBg)
                .border(1.dp, if (checked) t.primary else t.inputBorder, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (checked) Icon(Icons.Filled.Check, null, tint = if (t.isDark) Color.Black else Color.White, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(label, color = t.text, fontSize = 13.sp)
    }
}

// ---------------- Text input (.form-control) ----------------

@Composable
fun FormInput(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    number: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    small: Boolean = false,
    radius: Dp? = null,
    borderless: Boolean = false,
    onDone: (() -> Unit)? = null,
    fontSize: TextUnit = if (small) 13.sp else 15.sp,
    lineHeight: TextUnit = TextUnit.Unspecified,
    padding: PaddingValues = if (small) PaddingValues(horizontal = 10.dp, vertical = 7.dp) else PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    enabled: Boolean = true,
    italic: Boolean = false,
) {
    val t = T.c
    val shape = RoundedCornerShape(radius ?: t.radius.coerceAtMost(14.dp))
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        textStyle = TextStyle(
            color = t.inputText, fontSize = fontSize, fontFamily = t.font, lineHeight = lineHeight,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal
        ),
        cursorBrush = SolidColor(t.primary),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when { password -> KeyboardType.Password; number -> KeyboardType.Number; else -> KeyboardType.Text },
            imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = modifier
            .onFocusChangedCompat { focused = it }
            .then(if (borderless) Modifier else Modifier.clip(shape).background(t.inputBg).border(1.dp, if (focused) t.primary else t.inputBorder, shape))
            .defaultMinSize(minHeight = if (small) 31.dp else 43.dp),
        decorationBox = { inner ->
            Box(Modifier.padding(padding), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = t.muted, fontSize = fontSize, lineHeight = lineHeight)
                inner()
            }
        }
    )
}

fun Modifier.onFocusChangedCompat(cb: (Boolean) -> Unit): Modifier = this.onFocusChanged { cb(it.isFocused) }

// ---------------- Custom select (.custom-select) ----------------

@Composable
fun <T> CustomSelect(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val t = com.minashin1120.voxcribe.ui.theme.T.c
    var open by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == selected }?.second ?: ""
    val shape = RoundedCornerShape(t.radius.coerceAtMost(12.dp))
    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.65f).clip(shape).background(t.inputBg)
                .border(1.dp, if (open) t.primary else t.inputBorder, shape)
                .clickable(enabled = enabled) { open = !open }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = t.text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.KeyboardArrowDown, null, tint = Bs.secondary, modifier = Modifier.size(16.dp).rotate(if (open) 180f else 0f))
        }
        DropdownMenu(
            expanded = open, onDismissRequest = { open = false },
            modifier = Modifier.background(t.cardBg).border(1.dp, t.cardBorder, RoundedCornerShape(t.radius + 2.dp)).widthIn(min = 180.dp)
        ) {
            options.forEach { (v, l) ->
                DropdownMenuItem(
                    text = { Text(l, color = t.text, fontSize = 14.sp) },
                    onClick = { open = false; onSelect(v) },
                    modifier = Modifier.heightIn(min = 36.dp)
                )
            }
        }
    }
}

// ---------------- Alert (.alert) ----------------

enum class AlertKind(val bg: Color, val fg: Color) {
    INFO(Bs.infoBg, Bs.infoText), WARNING(Bs.warningBg, Bs.warningText), SUCCESS(Bs.successBg, Bs.successText), DANGER(Color(0xFFF8D7DA), Color(0xFF58151C))
}

@Composable
fun AlertBox(kind: AlertKind, icon: ImageVector?, text: String, modifier: Modifier = Modifier, fontSize: TextUnit = 12.sp) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(kind.bg).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = kind.fg, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = kind.fg, fontSize = fontSize)
    }
}

// ---------------- Modal (.modal) ----------------

@Composable
fun AppModal(
    title: String,
    onDismiss: () -> Unit,
    titleColor: Color? = null,
    titleIcon: ImageVector? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = T.c
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth().widthIn(max = 560.dp)
                .shadow(24.dp, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp)).background(if (t.isDark) Color(0xFF111111) else Color.White)
                .border(1.dp, t.cardBorder, RoundedCornerShape(20.dp))
        ) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (titleIcon != null) {
                    Icon(titleIcon, null, tint = titleColor ?: t.text, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(title, color = titleColor ?: t.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.Close, "閉じる", tint = t.text.copy(alpha = .6f), modifier = Modifier.size(22.dp).clickable { onDismiss() })
            }
            Box(Modifier.fillMaxWidth().size(width = 0.dp, height = 1.dp).background(t.cardBorder))
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(16.dp), content = content)
            if (footer != null) {
                Box(Modifier.fillMaxWidth().size(width = 0.dp, height = 1.dp).background(t.cardBorder))
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = footer
                )
            }
        }
    }
}

data class ConfirmRequest(val message: String, val onConfirm: () -> Unit)

/** window.confirm 相当 */
@Composable
fun ConfirmDialog(req: ConfirmRequest, onClose: () -> Unit) {
    val t = T.c
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (t.isDark) Color(0xFF111111) else Color.White)
                .border(1.dp, t.cardBorder, RoundedCornerShape(16.dp)).padding(20.dp)
        ) {
            Text(req.message, color = t.text, fontSize = 15.sp)
            Spacer(Modifier.size(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                BsButton("キャンセル", onClose, variant = BtnVariant.SECONDARY)
                BsButton("OK", { onClose(); req.onConfirm() }, variant = BtnVariant.PRIMARY)
            }
        }
    }
}

// ---------------- Toast host ----------------

@Composable
fun BoxScope.ToastHost(toaster: Toaster) {
    Column(
        Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp, start = 60.dp).widthIn(max = 350.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End
    ) {
        toaster.items.forEach { msg ->
            androidx.compose.runtime.key(msg.id) {
                var visible by remember { mutableStateOf(false) }
                LaunchedEffect(msg.id) {
                    visible = true
                    delay(if (msg.isError) 5000 else 3000)
                    visible = false
                    delay(300)
                    toaster.dismiss(msg.id)
                }
                AnimatedVisibility(visible, enter = fadeIn() + slideInHorizontally { it / 6 }, exit = fadeOut()) {
                    Row(
                        Modifier.shadow(16.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
                            .background(if (msg.isError) Bs.danger else Bs.dark)
                            .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(msg.text, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Filled.Close, null, tint = Color.White, modifier = Modifier.size(18.dp).clickable { toaster.dismiss(msg.id) })
                    }
                }
            }
        }
    }
}

// ---------------- Background (ambient orbs) ----------------

@Composable
fun AppBackground(content: @Composable BoxScope.() -> Unit) {
    val t = T.c
    val tr = rememberInfiniteTransition(label = "ambient")
    val p by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Reverse), label = "drift")
    Box(
        Modifier.fillMaxSize().background(t.bg).drawBehind {
            // body.app-body の放射グラデーション
            drawRect(Brush.radialGradient(listOf(Color(0x1A7D82FF), Color.Transparent), center = Offset(size.width * .08f, size.height * .12f), radius = size.maxDimension * .3f))
            drawRect(Brush.radialGradient(listOf(Color(0x1452C7B5), Color.Transparent), center = Offset(size.width * .94f, size.height * .82f), radius = size.maxDimension * .28f))
            // ambient orb（28rem, opacity .16, 16s ドリフト）
            val r = 224.dp.toPx()
            val dx = 55.dp.toPx() * p
            val dy = 35.dp.toPx() * p
            val s = 1f + 0.08f * p
            val c1 = Offset(-192.dp.toPx() + r + dx, -240.dp.toPx() + r + dy)
            drawCircle(Brush.radialGradient(listOf(t.primary.copy(alpha = .16f), Color.Transparent), center = c1, radius = r * s), radius = r * s, center = c1)
            val c2 = Offset(size.width + 224.dp.toPx() - r - dx, size.height + 256.dp.toPx() - r - dy)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF46BEA9).copy(alpha = .16f), Color.Transparent), center = c2, radius = r * s), radius = r * s, center = c2)
        },
        content = content
    )
}

// ---------------- Status dot ----------------

@Composable
fun StatusDot() {
    val tr = rememberInfiniteTransition(label = "pulse")
    val a by tr.animateFloat(1f, .45f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "pa")
    Canvas(Modifier.size(17.dp)) {
        drawCircle(Color(0xFF27B783).copy(alpha = .10f), radius = 8.5.dp.toPx())
        drawCircle(Color(0xFF27B783).copy(alpha = a), radius = 3.5.dp.toPx())
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().size(width = 0.dp, height = 1.dp).background(T.c.cardBorder))
}

@Composable
fun MutedText(text: String, modifier: Modifier = Modifier, fontSize: TextUnit = 12.sp) {
    Text(text, color = T.c.muted, fontSize = fontSize, modifier = modifier)
}

