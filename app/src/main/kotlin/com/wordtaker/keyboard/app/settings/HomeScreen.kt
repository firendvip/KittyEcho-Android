/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wordtaker.keyboard.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wordtaker.keyboard.R
import com.wordtaker.keyboard.app.LocalNavController
import com.wordtaker.keyboard.app.Routes
import com.wordtaker.keyboard.lib.compose.FlorisScreen
import com.wordtaker.keyboard.lib.util.InputMethodUtils
import dev.patrickgold.jetpref.datastore.ui.Preference
import com.wordtaker.lib.compose.FlorisCanvasIcon
import com.wordtaker.lib.compose.FlorisErrorCard
import com.wordtaker.lib.compose.FlorisWarningCard
import com.wordtaker.lib.compose.stringRes

@Composable
fun HomeScreen() = FlorisScreen {
    title = "弦外小猫"
    navigationIconVisible = false
    previewFieldVisible = false

    val navController = LocalNavController.current
    val context = LocalContext.current

    content {
        Column(
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 16.dp),
        ) {
            FlorisCanvasIcon(
                modifier = Modifier.requiredSize(80.dp),
                iconId = R.drawable.ic_brand_cat,
                contentDescription = "弦外小猫",
            )
            Text(
                text = "弦外小猫",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = "KittyEcho",
                fontSize = 14.sp,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = "已就绪 · 点按输入框里的小猫开始",
                fontSize = 13.sp,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        val isFlorisBoardEnabled by InputMethodUtils.observeIsFlorisboardEnabled(foregroundOnly = true)
        val isFlorisBoardSelected by InputMethodUtils.observeIsFlorisboardSelected(foregroundOnly = true)
        if (!isFlorisBoardEnabled) {
            FlorisErrorCard(
                modifier = Modifier.padding(8.dp),
                showIcon = false,
                text = "「弦外小猫」尚未在系统中启用，点此前往开启。",
                onClick = { InputMethodUtils.showImeEnablerActivity(context) },
            )
        } else if (!isFlorisBoardSelected) {
            FlorisWarningCard(
                modifier = Modifier.padding(8.dp),
                showIcon = false,
                text = "「弦外小猫」还不是当前输入法，点此切换。",
                onClick = { InputMethodUtils.showImePicker(context) },
            )
        }

        Text(
            text = "去试试：在任意输入框点按小猫，开口说话即可。",
            fontSize = 14.sp,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
        )
        Preference(
            icon = Icons.Outlined.Settings,
            title = "设置",
            onClick = { navController.navigate(Routes.Settings.MinimalSettings) },
        )
        Preference(
            icon = Icons.Outlined.Info,
            title = "关于",
            onClick = { navController.navigate(Routes.Settings.About) },
        )
    }
}
