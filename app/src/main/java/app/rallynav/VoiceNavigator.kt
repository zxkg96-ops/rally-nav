package app.rallynav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface VoiceStatus {
    data object Starting : VoiceStatus
    data object Ready : VoiceStatus
    data class Unavailable(val reason: String) : VoiceStatus
}

enum class Priority { LOW, NORMAL, HIGH }

/**
 * Голос штурмана. Все вызовы — из главного потока.
 * Своя очередь: приоритеты, защита от повторов, устаревшие команды отбрасываются.
 */
class VoiceNavigator(context: Context, private val settings: SettingsStore) {

    private class Item(
        val text: String,
        val priority: Priority,
        val key: String,
        val tag: String,
        val createdMs: Long,
        val maxAgeMs: Long,
    )

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val queue = ArrayList<Item>()
    private val lastSpoken = HashMap<String, Long>()
    private var speaking: Item? = null
    private var tts: TextToSpeech? = null
    private var focusRequest: AudioFocusRequest? = null
    private var consecutiveErrors = 0

    private val _status = MutableStateFlow<VoiceStatus>(VoiceStatus.Starting)
    val status: StateFlow<VoiceStatus> = _status

    private val watchdog = Runnable {
        // Если движок молчит слишком долго, не зависаем навсегда.
        speaking = null
        pump()
    }

    private fun now() = SystemClock.elapsedRealtime()

    init {
        tts = TextToSpeech(appContext) { code ->
            main.post { onEngineReady(code) }
        }
    }

    private fun onEngineReady(code: Int) {
        val engine = tts
        if (code != TextToSpeech.SUCCESS || engine == null) {
            _status.value = VoiceStatus.Unavailable("Синтез речи недоступен на этом телефоне.")
            return
        }
        val lang = engine.setLanguage(Locale.forLanguageTag("ru-RU"))
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            _status.value = VoiceStatus.Unavailable("Нет русского голоса. Установи голосовые данные в настройках речи.")
            return
        }
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                main.post {
                    consecutiveErrors = 0
                    finishCurrent()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { onSpeakError() }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                main.post { onSpeakError() }
            }
        })
        _status.value = VoiceStatus.Ready
        pump()
    }

    private fun onSpeakError() {
        consecutiveErrors++
        if (consecutiveErrors >= 3) {
            _status.value = VoiceStatus.Unavailable("Ошибка синтеза речи. Проверь настройки речи в телефоне.")
            queue.clear()
        }
        finishCurrent()
    }

    private fun finishCurrent() {
        main.removeCallbacks(watchdog)
        speaking = null
        if (queue.isEmpty()) abandonFocus()
        pump()
    }

    /** Поставить фразу в очередь. dedupeMs — сколько секунд не повторять фразу с тем же key. */
    fun say(
        text: String,
        priority: Priority,
        key: String,
        dedupeMs: Long = 10_000L,
        maxAgeMs: Long = 8_000L,
        tag: String = "",
    ) {
        if (!settings.voiceEnabled) return
        val t = now()
        val last = lastSpoken[key]
        if (last != null && t - last < dedupeMs) return
        if (queue.any { it.key == key } || speaking?.key == key) return
        lastSpoken[key] = t
        queue.add(Item(text, priority, key, tag, t, maxAgeMs))
        queue.sortWith(compareByDescending<Item> { it.priority }.thenBy { it.createdMs })
        val cur = speaking
        if (priority == Priority.HIGH && cur != null && cur.priority != Priority.HIGH) {
            tts?.stop()
            finishCurrent()
            return
        }
        pump()
    }

    /** Убрать из очереди и оборвать устаревшие команды (например, при смене маршрута). */
    fun clearTag(tag: String) {
        queue.removeAll { it.tag == tag }
        if (speaking?.tag == tag) {
            tts?.stop()
            finishCurrent()
        }
    }

    fun clearAll() {
        queue.clear()
        if (speaking != null) {
            tts?.stop()
            finishCurrent()
        }
    }

    private fun pump() {
        val engine = tts ?: return
        if (_status.value != VoiceStatus.Ready || speaking != null) return
        while (queue.isNotEmpty()) {
            val next = queue.removeAt(0)
            if (now() - next.createdMs > next.maxAgeMs) continue // команда устарела
            requestFocus()
            engine.setSpeechRate(settings.speechRate)
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, settings.volume)
            }
            speaking = next
            val result = engine.speak(next.text, TextToSpeech.QUEUE_FLUSH, params, next.key)
            if (result == TextToSpeech.SUCCESS) {
                main.postDelayed(watchdog, 15_000L)
                return
            }
            speaking = null
            onSpeakError()
            return
        }
        abandonFocus()
    }

    private fun requestFocus() {
        if (focusRequest != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        audio.requestAudioFocus(req)
        focusRequest = req
    }

    private fun abandonFocus() {
        focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    fun shutdown() {
        main.removeCallbacks(watchdog)
        queue.clear()
        tts?.stop()
        tts?.shutdown()
        tts = null
        abandonFocus()
    }
}
