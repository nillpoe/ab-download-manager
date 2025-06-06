package com.abdownloadmanager.desktop.pages.settings

import com.abdownloadmanager.desktop.utils.configurable.RenderConfigurable
import com.abdownloadmanager.shared.utils.ui.WithContentAlpha
import com.abdownloadmanager.desktop.window.custom.WindowIcon
import com.abdownloadmanager.desktop.window.custom.WindowTitle
import ir.amirab.util.compose.IconSource
import com.abdownloadmanager.shared.utils.ui.widget.MyIcon
import com.abdownloadmanager.shared.utils.ui.icon.MyIcons
import com.abdownloadmanager.shared.utils.ui.myColors
import com.abdownloadmanager.shared.ui.widget.Handle
import com.abdownloadmanager.shared.ui.widget.Text
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.abdownloadmanager.resources.Res
import com.abdownloadmanager.shared.utils.div
import com.abdownloadmanager.shared.utils.ui.theme.myTextSizes
import ir.amirab.util.compose.resources.myStringResource
import ir.amirab.util.ifThen

@Composable
private fun SideBar(
    settingsComponent: SettingsComponent,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier
            .fillMaxHeight()
            .border(1.dp, myColors.surface / 0.5f, shape)
            .clip(shape)
    ) {
//        var searchText by remember { mutableStateOf("") }
//        SearchBox(
//            searchText,
//            onTextChange = { searchText = it },
//            modifier = Modifier.height(38.dp),
//        )
        for (i in settingsComponent.pages) {
            SideBarItem(
                icon = i.icon,
                name = i.name.rememberString(),
                isSelected = settingsComponent.currentPage == i,
                onClick = {
                    settingsComponent.currentPage = i
                }
            )
        }
    }
}

@Composable
private fun SideBarItem(icon: IconSource, name: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(IntrinsicSize.Max)
            .ifThen(isSelected) {
                background(myColors.onBackground / 0.05f)
            }
            .selectable(
                selected = isSelected,
                onClick = onClick
            )
    ) {
        Row(
            Modifier
                .padding(vertical = 8.dp)
                .padding(start = 16.dp)
                .padding(end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WithContentAlpha(if (isSelected) 1f else 0.75f) {
                MyIcon(
                    icon,
                    null,
                    Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    name,
                    Modifier.weight(1f),
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = myTextSizes.lg,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
            }
        }
        AnimatedVisibility(
            isSelected,
            modifier = Modifier
                .align(Alignment.CenterStart),
            enter = scaleIn(),
            exit = scaleOut(),
        ) {
            Spacer(
                Modifier
                    .height(16.dp)
                    .width(3.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 0.dp,
                            bottomStart = 0.dp,
                            bottomEnd = 12.dp,
                            topEnd = 12.dp,
                        )
                    )
                    .background(myColors.primary)
            )
        }
        if (isSelected) {
            listOf(
                Alignment.TopCenter,
                Alignment.BottomCenter,
            ).forEach {
                Spacer(
                    Modifier
                        .align(it)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    myColors.onBackground / 0.1f,
                                    myColors.onBackground / 0.1f,
                                    Color.Transparent,
                                )
                            )
                        )
                )
            }
        }
    }
}

@Composable
fun SettingsPage(
    settingsComponent: SettingsComponent,
    onDismissRequest: () -> Unit,
) {
    WindowTitle(myStringResource(Res.string.settings))
//    WindowIcon(MyIcons.settings)
    WindowIcon(MyIcons.appIcon)
    Column {
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(myColors.surface)
        )
        Row {
            var sideBarWidth by remember { mutableStateOf(250.dp) }
            SideBar(
                settingsComponent,
                Modifier
                    .fillMaxHeight()
                    .width(sideBarWidth)
                    .padding(8.dp)
            )
            val currentConfigurables = settingsComponent.configurables
            Handle(
                Modifier.width(5.dp).fillMaxHeight(),
                orientation = Orientation.Horizontal
            ) {
                sideBarWidth = (sideBarWidth + it).coerceIn(150.dp..300.dp)
            }
            AnimatedContent(currentConfigurables) { configurables ->
                val scrollState = rememberScrollState()
                val scrollbarAdapter = rememberScrollbarAdapter(scrollState)
                Box {
                    Column(
                        Modifier
                            .verticalScroll(scrollState)
                            .padding(
                                horizontal = 16.dp,
                                vertical = 8.dp
                            )
                    ) {
                        for (cfg in configurables) {
                            Box(
                                Modifier
                                    .background(myColors.surface / 50)
                            ) {
                                RenderConfigurable(cfg, Modifier.padding(vertical = 16.dp, horizontal = 32.dp))
                            }
                            Spacer(Modifier.height(1.dp))

//                    Divider()
                        }
                    }
                    VerticalScrollbar(
                        adapter = scrollbarAdapter,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(vertical = 16.dp)
                            .padding(end = 2.dp),
                    )
                }
            }

        }
    }
}



