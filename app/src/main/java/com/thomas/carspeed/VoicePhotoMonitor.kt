package com.thomas.carspeed

data class VoicePhotoDebugState(
    val previewListening: Boolean = false,
    val serviceListening: Boolean = false,
    val recognitionAvailable: Boolean? = null,
    val onDeviceRecognitionAvailable: Boolean? = null,
    val lastEngine: String? = null,
    val lastOwner: String? = null,
    val lastErrorCode: Int? = null,
    val lastErrorLabel: String? = null,
    val lastHeardText: String? = null,
    val lastTriggeredAtMs: Long = 0L,
    val lastStartedAtMs: Long = 0L
)

object VoicePhotoMonitor {
    private const val OWNER_PREVIEW = "preview"
    private const val OWNER_SERVICE = "service"

    @Volatile private var state = VoicePhotoDebugState()

    fun currentState(): VoicePhotoDebugState = state

    fun setRecognitionAvailable(available: Boolean, onDeviceAvailable: Boolean?) {
        state = state.copy(
            recognitionAvailable = available,
            onDeviceRecognitionAvailable = onDeviceAvailable
        )
    }

    fun markStarted(owner: String, engine: String) {
        state = state.copy(
            previewListening = if (owner == OWNER_PREVIEW) true else state.previewListening,
            serviceListening = if (owner == OWNER_SERVICE) true else state.serviceListening,
            lastEngine = engine,
            lastOwner = owner,
            lastErrorCode = null,
            lastErrorLabel = null,
            lastStartedAtMs = System.currentTimeMillis()
        )
    }

    fun markStopped(owner: String) {
        state = state.copy(
            previewListening = if (owner == OWNER_PREVIEW) false else state.previewListening,
            serviceListening = if (owner == OWNER_SERVICE) false else state.serviceListening,
            lastOwner = owner
        )
    }

    fun markError(owner: String, code: Int, label: String = errorLabel(code)) {
        state = state.copy(
            previewListening = if (owner == OWNER_PREVIEW) false else state.previewListening,
            serviceListening = if (owner == OWNER_SERVICE) false else state.serviceListening,
            lastOwner = owner,
            lastErrorCode = code,
            lastErrorLabel = label
        )
    }

    fun markHeard(owner: String, text: String) {
        state = state.copy(lastOwner = owner, lastHeardText = text)
    }

    fun markTriggered(owner: String) {
        state = state.copy(lastOwner = owner, lastTriggeredAtMs = System.currentTimeMillis())
    }

    private fun errorLabel(code: Int): String {
        return when (code) {
            -2 -> "启动异常"
            -1 -> "识别服务不可用"
            1 -> "网络超时"
            2 -> "网络错误"
            3 -> "录音错误"
            4 -> "服务端错误"
            5 -> "客户端错误"
            6 -> "未检测到语音"
            7 -> "无识别结果"
            8 -> "识别服务忙"
            9 -> "权限不足"
            10 -> "请求过于频繁"
            11 -> "识别服务断开"
            12 -> "语言不支持"
            13 -> "语言不可用"
            else -> "错误 $code"
        }
    }
}
