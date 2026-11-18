package com.android.catboxclient

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/*
 * 背景上傳（依 Android 官方「Data transfer background task options」建議）
 *
 *  - 使用者主動觸發、需要顯示進度、被系統中斷會影響體驗
 *      → Android 14+（API 34）：User-Initiated Data Transfer (UIDT) job
 *      → Android 13 以下：WorkManager（expedited + foreground service，dataSync）
 *  - 兩條路徑共用 UploadRunner，狀態透過 UploadStateHolder 同步給 UI。
 */

// ───────── 資料與狀態 ─────────

data class UploadRequest(
    val uri: Uri,
    val service: ServiceType,
    val time: LitterboxTime
)

/** 同一個行程內，Service / Worker 與 ViewModel 共用的上傳狀態。 */
object UploadStateHolder {
    private val _state = MutableStateFlow<UploadState>(UploadState.Idle)
    val state: StateFlow<UploadState> = _state.asStateFlow()

    fun set(state: UploadState) {
        _state.value = state
    }

    fun reset() = set(UploadState.Idle)
}

// ───────── 通知 ─────────

object UploadNotifications {
    private const val CHANNEL_ID = "uploads"
    const val PROGRESS_ID = 2001
    private const val RESULT_ID = 2002

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannelCompat.Builder(
            CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW
        ).setName(context.getString(R.string.notification_channel_name)).build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** 進度通知，含「取消」按鈕（官方建議：提供在通知上優雅停止的方式）。 */
    fun progress(context: Context, percent: Int): Notification {
        ensureChannel(context)
        val cancel = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, UploadCancelReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(context.getString(R.string.state_uploading))
            .setContentText(context.getString(R.string.notification_progress, percent))
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(context))
            .addAction(0, context.getString(R.string.action_cancel), cancel)
            .build()
    }

    @SuppressLint("MissingPermission")
    fun notifyResult(
        context: Context,
        title: String,
        text: String,
        success: Boolean,
        copyUrl: String? = null
    ) {
        ensureChannel(context)
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(
                if (success) android.R.drawable.stat_sys_upload_done
                else android.R.drawable.stat_notify_error
            )
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))

        // 上傳成功時提供「複製連結」按鈕
        if (copyUrl != null) {
            val copy = PendingIntent.getBroadcast(
                context, 1,
                Intent(context, UploadCopyReceiver::class.java)
                    .putExtra(UploadCopyReceiver.EXTRA_URL, copyUrl),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, context.getString(R.string.action_copy), copy)
        }
        nm.notify(RESULT_ID, builder.build())
    }
}

/** 通知上的「複製連結」：把網址寫入剪貼簿。 */
class UploadCopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("URL", url))
        // Android 13+ 系統會自行顯示已複製的提示，舊版才需要自己顯示
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.toast_copied, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_URL = "extra_url"
    }
}

class UploadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        UploadScheduler.cancel(context)
    }
}

// ───────── 共用的上傳流程 ─────────

object UploadRunner {

    /** @return 是否上傳成功。取消時會丟出 CancellationException（不改動狀態）。 */
    suspend fun run(
        context: Context,
        request: UploadRequest,
        onPercent: (Int) -> Unit = {}
    ): Boolean {
        val app = context.applicationContext
        UploadStateHolder.set(UploadState.Loading(0f))
        var lastPercent = -1

        return try {
            val url = withContext(Dispatchers.IO) {
                NativeUploader.upload(
                    contentResolver = app.contentResolver,
                    uri = request.uri,
                    serviceType = request.service,
                    time = request.time,
                    userHash = PreferenceRepository(app).getUserHash(),
                    onProgress = { progress ->
                        UploadStateHolder.set(UploadState.Loading(progress))
                        val percent = (progress * 100).toInt()
                        if (percent != lastPercent) { // 避免過度更新通知
                            lastPercent = percent
                            onPercent(percent)
                        }
                    }
                )
            }
            val qr = withContext(Dispatchers.Default) { QrCodeGenerator.generate(url, size = 300) }

            UploadStateHolder.set(UploadState.Success(url, qr))
            UploadNotifications.notifyResult(
                app, app.getString(R.string.state_success), url, success = true, copyUrl = url
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: UploadException) {
            fail(app, e.messageRes, e.args)
        } catch (e: IOException) {
            fail(app, R.string.error_network, listOf(e.message.orEmpty()))
        } catch (_: Exception) {
            fail(app, R.string.error_unknown)
        }
    }

    private fun fail(app: Context, resId: Int, args: List<Any> = emptyList()): Boolean {
        UploadStateHolder.set(UploadState.Error(resId, args))
        UploadNotifications.notifyResult(
            app,
            app.getString(R.string.state_failed),
            app.getString(resId, *args.toTypedArray()),
            false
        )
        return false
    }
}

// ───────── 排程（依 API 等級選擇實作） ─────────

object UploadScheduler {
    private const val JOB_ID = 1001
    private const val WORK_NAME = "catbox_upload"

    const val KEY_URI = "uri"
    const val KEY_SERVICE = "service"
    const val KEY_TIME = "time"

    /** 必須在 App 可見時呼叫（UIDT 的限制），例如檔案選擇器回傳後。 */
    fun enqueue(context: Context, request: UploadRequest): Boolean {
        val app = context.applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            scheduleUserInitiatedJob(app, request)
        } else {
            scheduleWorker(app, request)
        }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            app.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        } else {
            WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
        }
        UploadStateHolder.reset()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun scheduleUserInitiatedJob(app: Context, request: UploadRequest): Boolean {
        val size = NativeUploader.queryFileSize(app.contentResolver, request.uri)

        val extras = PersistableBundle().apply {
            putString(KEY_SERVICE, request.service.name)
            putString(KEY_TIME, request.time.name)
        }
        val network = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val jobInfo = JobInfo.Builder(JOB_ID, ComponentName(app, UploadJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetwork(network)
            .setExtras(extras)
            // 透過 ClipData 把檔案 Uri 的讀取權限交給 Job，系統重試時仍然有效
            .setClipData(
                ClipData.newRawUri(null, request.uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            // 預估流量（下載, 上傳），讓系統判斷該給多少執行時間
            .setEstimatedNetworkBytes(
                1024L,
                if (size >= 0) size else JobInfo.NETWORK_BYTES_UNKNOWN.toLong()
            )
            .build()

        val scheduler = app.getSystemService(JobScheduler::class.java)
        return scheduler.schedule(jobInfo) == JobScheduler.RESULT_SUCCESS
    }

    private fun scheduleWorker(app: Context, request: UploadRequest): Boolean {
        val work = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(
                workDataOf(
                    KEY_URI to request.uri.toString(),
                    KEY_SERVICE to request.service.name,
                    KEY_TIME to request.time.name
                )
            )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(app)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, work)
        return true
    }
}

// ───────── Android 14+：User-Initiated Data Transfer Job ─────────

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class UploadJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val uri = params.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri ?: return false
        val service = params.extras.getString(UploadScheduler.KEY_SERVICE)
            ?.let { ServiceType.valueOf(it) } ?: return false
        val time = params.extras.getString(UploadScheduler.KEY_TIME)
            ?.let { LitterboxTime.valueOf(it) } ?: LitterboxTime.HOUR_1

        // 讓使用者知道工作正在執行（工作管理員 + 狀態列）
        setNotification(
            params, UploadNotifications.PROGRESS_ID,
            UploadNotifications.progress(this, 0),
            JOB_END_NOTIFICATION_POLICY_REMOVE
        )

        // 在背景執行，避免卡住主執行緒造成 ANR
        running[params.jobId] = scope.launch {
            UploadRunner.run(applicationContext, UploadRequest(uri, service, time)) { percent ->
                setNotification(
                    params, UploadNotifications.PROGRESS_ID,
                    UploadNotifications.progress(applicationContext, percent),
                    JOB_END_NOTIFICATION_POLICY_REMOVE
                )
            }
            running.remove(params.jobId)
            jobFinished(params, false) // 不論成功或失敗都立即結束，避免佔用資源
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        // 使用者在通知上按取消 → 不重試；系統中斷（限制條件不符、過熱等）→ 交給系統重新排程
        return params.stopReason != JobParameters.STOP_REASON_CANCELLED_BY_APP
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

// ───────── Android 13 以下：WorkManager + 前景服務 ─────────

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0)

    override suspend fun doWork(): Result {
        val uri = inputData.getString(UploadScheduler.KEY_URI)?.let(Uri::parse)
            ?: return Result.failure()
        val service = inputData.getString(UploadScheduler.KEY_SERVICE)
            ?.let { ServiceType.valueOf(it) } ?: return Result.failure()
        val time = inputData.getString(UploadScheduler.KEY_TIME)
            ?.let { LitterboxTime.valueOf(it) } ?: LitterboxTime.HOUR_1

        try {
            setForeground(foregroundInfo(0))
        } catch (_: IllegalStateException) {
            // 無法升級為前景服務時仍繼續執行（可能被系統提早中斷）
        }

        val ok =
            UploadRunner.run(applicationContext, UploadRequest(uri, service, time)) { percent ->
                setForegroundAsync(foregroundInfo(percent))
            }
        return if (ok) Result.success() else Result.failure()
    }

    private fun foregroundInfo(percent: Int): ForegroundInfo {
        val notification = UploadNotifications.progress(applicationContext, percent)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                UploadNotifications.PROGRESS_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(UploadNotifications.PROGRESS_ID, notification)
        }
    }
}