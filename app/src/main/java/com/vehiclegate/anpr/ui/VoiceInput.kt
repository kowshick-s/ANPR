package com.vehiclegate.anpr.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.MotionEvent
import android.widget.TextView

/**
 * In-app speech recognition (no pop-up): listens once and returns the recogniser's alternatives.
 * Uses the phone's speech service (Google) – Tamil + English mixed works best online.
 */
class VoiceInput(private val activity: Activity, private val status: (String) -> Unit) {

    private var recognizer: SpeechRecognizer? = null

    fun available() = SpeechRecognizer.isRecognitionAvailable(activity)

    fun hasPermission() = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun listen(language: String, prompt: String, onResult: (List<String>) -> Unit) {
        if (!available()) { status("Voice input is not available on this device (needs Google speech services)"); return }
        if (!hasPermission()) {
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST)
            status("Allow the microphone, then tap the mic again")
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(activity).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = status("🎤 Listening… $prompt")
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = status("Got it – checking…")
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) {
                status(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that – tap the mic and speak again"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice needs internet – check the connection"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow the microphone in the app permissions"
                    else -> "Voice input error ($error) – try again"
                })
            }
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                if (list.isEmpty()) status("Didn't catch that – tap the mic and speak again") else onResult(list)
            }
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
        })
    }

    fun destroy() { recognizer?.destroy(); recognizer = null }

    companion object {
        const val REQUEST = 41

        /** Tap on the mic icon at the end of a text box → [onMic]; elsewhere the box works normally. */
        @SuppressLint("ClickableViewAccessibility")
        fun attachMic(field: TextView, onMic: () -> Unit) {
            field.setOnTouchListener { v, e ->
                val d = (v as TextView).compoundDrawablesRelative[2] ?: return@setOnTouchListener false
                val onIcon = e.x >= v.width - v.paddingEnd - d.bounds.width() - 16
                if (onIcon && e.action == MotionEvent.ACTION_UP) { onMic(); true } else onIcon
            }
        }
    }
}
