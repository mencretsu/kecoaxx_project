package com.example.ngontol

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.TextView

class BotKeyboard : InputMethodService() {

    companion object {
        var instance: BotKeyboard? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    override fun onCreateInputView(): View {
        // Layout keyboard custom (minimalis)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111111.toInt())

            val label = TextView(context).apply {
                text = "⚡ KECOAXX IS FUCKIN RUNNING..."
                setTextColor(0xFF00FF66.toInt())
                textSize = 16f
                setPadding(24, 24, 24, 24)
            }
            addView(label)
        }
        return layout
    }

    /** Dipanggil dari service untuk ngetik otomatis */
    fun typeText(text: String) {
        val ic: InputConnection? = currentInputConnection
        if (ic != null) {
            ic.commitText(text as CharSequence?, 1)
        }
    }

}
