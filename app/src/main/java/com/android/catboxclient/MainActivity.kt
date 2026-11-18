package com.android.catboxclient

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.catboxclient.ui.theme.CatboxTheme
import com.android.catboxclient.ui.theme.extendedColors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CatboxTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CatboxScreen()
                }
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        CustomTabsIntent.Builder().build().launchUrl(context, url.toUri())
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.error_open_link, Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatboxScreen(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.uploadState.collectAsStateWithLifecycle()
    val options by viewModel.options.collectAsStateWithLifecycle()

    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { viewModel.uploadFile(context.applicationContext, it) }
        }

// Android 13+ 通知權限
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    var permissionAsked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!permissionAsked &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionAsked = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {}, actions = {
                    IconButton(onClick = {
                        context.startActivity(
                            Intent(
                                context, SettingsActivity::class.java
                            )
                        )
                    }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.label_settings)
                        )
                    }
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (val currentState = state) {
                is UploadState.Idle -> {
                    Icon(
                        imageVector = Icons.Default.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(100.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.title_catbox_uploader),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(48.dp))

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = options.service == ServiceType.CATBOX,
                            onClick = { viewModel.onServiceSelected(ServiceType.CATBOX) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) { Text(stringResource(R.string.service_catbox)) }
                        SegmentedButton(
                            selected = options.service == ServiceType.LITTERBOX,
                            onClick = { viewModel.onServiceSelected(ServiceType.LITTERBOX) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) { Text(stringResource(R.string.service_litterbox)) }
                    }

                    Box(
                        modifier = Modifier
                            .heightIn(min = 50.dp)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (options.service == ServiceType.LITTERBOX) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    LitterboxTime.entries.forEach { time ->
                                        FilterChip(
                                            selected = options.litterboxTime == time,
                                            onClick = { viewModel.onLitterboxTimeSelected(time) },
                                            label = { Text(stringResource(time.labelRes)) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { filePickerLauncher.launch("*/*") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(
                            stringResource(R.string.btn_send),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                is UploadState.Loading -> {
                    Text(
                        stringResource(R.string.state_uploading),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(
                            R.string.upload_progress_percent,
                            (currentState.progress * 100).toInt()
                        ),
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    LinearProgressIndicator(
                        progress = { currentState.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .padding(horizontal = 16.dp),
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                    Spacer(Modifier.height(24.dp))
                    TextButton(onClick = { viewModel.cancelUpload(context.applicationContext) }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }

                is UploadState.Success -> {
                    var showQrDialog by rememberSaveable { mutableStateOf(false) }
                    if (showQrDialog) {
                        AlertDialog(
                            onDismissRequest = { showQrDialog = false },
                            confirmButton = {
                                TextButton(onClick = { showQrDialog = false }) {
                                    Text(stringResource(R.string.action_close))
                                }
                            },
                            title = {
                                Text(
                                    stringResource(R.string.dialog_qr_title),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            text = {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    androidx.compose.foundation.Image(
                                        bitmap = currentState.qrBitmap.asImageBitmap(),
                                        contentDescription = stringResource(R.string.content_description_qr),
                                        modifier = Modifier.size(200.dp)
                                    )
                                }
                            })
                    }

                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.extendedColors.success
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.state_success),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = currentState.url,
                            modifier = Modifier
                                .padding(24.dp)
                                .fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        TextButton(onClick = {
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(
                                android.content.ClipData.newPlainText(
                                    "URL", currentState.url
                                )
                            )
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_copied),
                                Toast.LENGTH_SHORT
                            ).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_copy))
                        }
                        TextButton(onClick = { showQrDialog = true }) {
                            Icon(imageVector = Icons.Default.QrCode, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.action_qr_code))
                        }
                        TextButton(onClick = { openUrl(context, currentState.url) }) {
                            Icon(Icons.Default.OpenInBrowser, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_open))
                        }
                    }
                    Spacer(modifier = Modifier.height(48.dp))
                    Button(onClick = { viewModel.resetState() }) {
                        Text(stringResource(R.string.action_done))
                    }
                }

                is UploadState.Error -> {
                    Text(
                        stringResource(R.string.state_failed),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(currentState.messageRes, *currentState.args.toTypedArray()),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(onClick = { viewModel.resetState() }) {
                        Text(stringResource(R.string.btn_try_again))
                    }
                }

            }
        }
    }
}