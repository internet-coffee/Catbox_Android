package com.android.catboxclient

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class UploadOptions(
    val service: ServiceType = ServiceType.CATBOX,
    val litterboxTime: LitterboxTime = LitterboxTime.HOUR_1
)

/**
 * 保存使用者選擇（服務、Litterbox 保存時間）
 * 送出上傳請求
 * 觀察 UploadStateHolder 的狀態給 UI
 */
class MainViewModel : ViewModel() {
    val uploadState: StateFlow<UploadState> = UploadStateHolder.state

    private val _options = MutableStateFlow(UploadOptions())
    val options: StateFlow<UploadOptions> = _options.asStateFlow()

    fun onServiceSelected(service: ServiceType) =
        _options.update { it.copy(service = service) }

    fun onLitterboxTimeSelected(time: LitterboxTime) =
        _options.update { it.copy(litterboxTime = time) }

    fun uploadFile(context: Context, uri: Uri) {
        if (uploadState.value is UploadState.Loading) return

        UploadStateHolder.set(UploadState.Loading(0f))
        val options = _options.value
        val scheduled = UploadScheduler.enqueue(
            context, UploadRequest(uri, options.service, options.litterboxTime)
        )
        if (!scheduled) {
            UploadStateHolder.set(UploadState.Error(R.string.error_schedule_failed))
        }
    }

    fun cancelUpload(context: Context) = UploadScheduler.cancel(context)

    fun resetState() = UploadStateHolder.reset()
}

sealed interface UploadState {
    data object Idle : UploadState
    data class Loading(val progress: Float) : UploadState
    data class Success(val url: String, val qrBitmap: Bitmap) : UploadState
    data class Error(
        @StringRes val messageRes: Int,
        val args: List<Any> = emptyList()
    ) : UploadState
}