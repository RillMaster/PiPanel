package com.rillmaster.pipanel.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rillmaster.pipanel.R

// ── Écran de verrouillage biométrique ─────────────────────────────────────
@Composable
fun BiometricLockScreen(error: String?, onRetry: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier            = Modifier.padding(40.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.logo),
                        contentDescription = stringResource(R.string.logo_content_description),
                        modifier = Modifier.size(70.dp),
                        contentScale = ContentScale.Fit
                    )
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier
                            .size(30.dp)
                            .align(Alignment.BottomEnd)
                            .offset(x = (-5).dp, y = (-5).dp)
                            .background(MaterialTheme.colorScheme.background, CircleShape)
                            .padding(4.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.auth_required),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (error != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text     = error,
                            modifier = Modifier.padding(12.dp),
                            color    = MaterialTheme.colorScheme.onErrorContainer,
                            style    = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        }
    }
}
