package com.rillmaster.pipanel.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rillmaster.pipanel.SettingsManager
import com.rillmaster.pipanel.vpn.WgTunnelManager
import com.wireguard.crypto.KeyPair
import com.wireguard.crypto.Key

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WireGuardEditorScreen(
    settings: SettingsManager,
    onBack: () -> Unit,
    onSave: () -> Unit
) {
    var vpnName by remember { mutableStateOf(settings.getCurrentProfile()?.name ?: "PiPanel") }
    var privateKey by remember { mutableStateOf(settings.vpnPrivateKey) }
    var publicKey by remember { 
        mutableStateOf(
            try { KeyPair(Key.fromBase64(settings.vpnPrivateKey)).publicKey.toBase64() } catch(_: Exception) { settings.vpnPublicKey }
        ) 
    }

    LaunchedEffect(privateKey) {
        try {
            publicKey = KeyPair(Key.fromBase64(privateKey)).publicKey.toBase64()
        } catch (_: Exception) {}
    }
    var addresses by remember { mutableStateOf(settings.vpnInterfaceAddresses) }
    var dnsServers by remember { mutableStateOf(settings.vpnInterfaceDns) }
    var listenPort by remember { mutableStateOf(if (settings.vpnInterfaceListenPort == 0) "" else settings.vpnInterfaceListenPort.toString()) }
    var mtu by remember { mutableStateOf(if (settings.vpnInterfaceMtu == 0) "" else settings.vpnInterfaceMtu.toString()) }

    var peerPublicKey by remember { mutableStateOf(settings.vpnPeerPublicKey) }
    var peerPresharedKey by remember { mutableStateOf(settings.vpnPeerPresharedKey) }
    var peerKeepalive by remember { mutableStateOf(if (settings.vpnPeerPersistentKeepalive == 0) "" else settings.vpnPeerPersistentKeepalive.toString()) }
    var peerEndpoint by remember { mutableStateOf(settings.vpnPeerEndpoint) }
    var peerAllowedIPs by remember { mutableStateOf(settings.vpnPeerAllowedIPs) }

    fun generateNewKeys() {
        val kp = KeyPair()
        privateKey = kp.privateKey.toBase64()
        publicKey = kp.publicKey.toBase64()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Créer un tunnel WireGuard...", fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        settings.vpnPrivateKey = privateKey
                        settings.vpnPublicKey = publicKey
                        settings.vpnInterfaceAddresses = addresses
                        settings.vpnInterfaceDns = dnsServers
                        settings.vpnInterfaceListenPort = listenPort.toIntOrNull() ?: 0
                        settings.vpnInterfaceMtu = mtu.toIntOrNull() ?: 0
                        
                        settings.vpnPeerPublicKey = peerPublicKey
                        settings.vpnPeerPresharedKey = peerPresharedKey
                        settings.vpnPeerPersistentKeepalive = peerKeepalive.toIntOrNull() ?: 0
                        settings.vpnPeerEndpoint = peerEndpoint
                        settings.vpnPeerAllowedIPs = peerAllowedIPs
                        
                        // Générer la config string pour le SDK
                        val config = """
                            [Interface]
                            PrivateKey = $privateKey
                            Address = $addresses
                            ${if (dnsServers.isNotBlank()) "DNS = $dnsServers" else ""}
                            ${if (listenPort.isNotBlank()) "ListenPort = $listenPort" else ""}
                            ${if (mtu.isNotBlank()) "MTU = $mtu" else ""}

                            [Peer]
                            PublicKey = $peerPublicKey
                            ${if (peerPresharedKey.isNotBlank()) "PresharedKey = $peerPresharedKey" else ""}
                            ${if (peerKeepalive.isNotBlank()) "PersistentKeepalive = $peerKeepalive" else ""}
                            Endpoint = $peerEndpoint
                            AllowedIPs = $peerAllowedIPs
                        """.trimIndent()
                        settings.vpnConfig = config
                        
                        onSave()
                    }) {
                        Icon(Icons.Default.Save, contentDescription = "Enregistrer")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color(0xFF121212)) // Dark background as in screenshots
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- SECTION INTERFACE ---
            Text("Interface", fontWeight = FontWeight.Bold, color = Color.White)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = vpnName,
                        onValueChange = { vpnName = it },
                        label = { Text("Nom") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = privateKey,
                        onValueChange = { privateKey = it },
                        label = { Text("Clé privée") },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { generateNewKeys() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Générer")
                            }
                        }
                    )

                    OutlinedTextField(
                        value = publicKey,
                        onValueChange = { /* Read only or derived */ },
                        label = { Text("Clé publique") },
                        placeholder = { Text("(généré)") },
                        modifier = Modifier.fillMaxWidth(),
                        readOnly = true
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = addresses,
                            onValueChange = { addresses = it },
                            label = { Text("Adresses") },
                            modifier = Modifier.weight(1.5f)
                        )
                        OutlinedTextField(
                            value = listenPort,
                            onValueChange = { listenPort = it },
                            label = { Text("Port d'éco...") },
                            placeholder = { Text("(aléatoire)") },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = dnsServers,
                            onValueChange = { dnsServers = it },
                            label = { Text("Serveurs DNS") },
                            modifier = Modifier.weight(1.5f)
                        )
                        OutlinedTextField(
                            value = mtu,
                            onValueChange = { mtu = it },
                            label = { Text("MTU") },
                            placeholder = { Text("(auto)") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Text("Toutes les Applications", modifier = Modifier.align(Alignment.CenterHorizontally), color = Color.Gray, fontSize = 14.sp)

            // --- SECTION PEER ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Pair", fontWeight = FontWeight.Bold, color = Color.White)
                IconButton(onClick = { /* Delete logic if multiple peers */ }) {
                    Icon(Icons.Default.Delete, contentDescription = "Supprimer", tint = Color.Gray)
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = peerPublicKey,
                        onValueChange = { peerPublicKey = it },
                        label = { Text("Clé publique") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = peerPresharedKey,
                        onValueChange = { peerPresharedKey = it },
                        label = { Text("Clé pré-partagée") },
                        placeholder = { Text("(facultatif)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = peerKeepalive,
                        onValueChange = { peerKeepalive = it },
                        label = { Text("Maintien de connexion permanente") },
                        placeholder = { Text("(facultatif, non recommandé)") },
                        suffix = { Text("seconde") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = peerEndpoint,
                        onValueChange = { peerEndpoint = it },
                        label = { Text("Point de terminaison") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = peerAllowedIPs,
                        onValueChange = { peerAllowedIPs = it },
                        label = { Text("Adresses IP autorisées") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            TextButton(
                onClick = { /* Add peer logic */ },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Ajouter un pair", color = Color.White)
            }
        }
    }
}
