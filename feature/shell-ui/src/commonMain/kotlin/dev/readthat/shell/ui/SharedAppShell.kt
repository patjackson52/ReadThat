package dev.readthat.shell.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.readthat.core.ui.brand.ReadThatLogo
import dev.readthat.core.ui.typography.ReadThatTextStyles
import dev.readthat.navigation.AppDestination
import dev.readthat.navigation.AppNavigationPolicy

/** Canonical four-item root information architecture shared by every navigation host. */
@Composable
fun SharedBottomNavigation(
    selected: AppDestination?,
    onNavigate: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    NavigationBar(modifier) {
        AppNavigationPolicy.primaryNavigationDestinations.forEach { destination ->
            val (icon, label) = when (destination) {
                AppDestination.Feed -> Icons.Default.Home to "Home"
                is AppDestination.CreatePost -> Icons.Default.Add to "Create"
                AppDestination.Activity -> Icons.Default.Notifications to "Activity"
                AppDestination.Profile -> Icons.Default.Person to "You"
                else -> error("Unsupported primary destination: $destination")
            }
            NavigationBarItem(
                selected = destination !is AppDestination.CreatePost && selected == destination,
                enabled = enabled,
                onClick = { onNavigate(destination) },
                icon = { Icon(icon, null) },
                label = { Text(label, style = ReadThatTextStyles.bottomNavigationLabel) },
            )
        }
    }
}

/** Static first-frame pixels that never wait for Keychain/Keystore, Room, or networking. */
@Composable
fun SharedStartupShell(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReadThatLogo(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)))
            Text(
                "ReadThat",
                Modifier.padding(start = 10.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
            )
        }
        repeat(3) { index ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                    .height(if (index == 0) 172.dp else 116.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
            ) {}
        }
        Spacer(Modifier.weight(1f))
        SharedBottomNavigation(
            selected = AppDestination.Feed,
            onNavigate = {},
            enabled = false,
        )
    }
}

@Composable
fun SharedActivityScreen(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Nothing new yet")
    }
}
