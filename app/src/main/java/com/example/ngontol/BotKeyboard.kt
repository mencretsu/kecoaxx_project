package com.example.ngontol

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView

class BotKeyboard : InputMethodService() {

    companion object {
        var instance: BotKeyboard? = null
            private set
    }

    private var isCaps = false

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    private fun Button.styleButton(
        bgColor: Int = 0xFF222222.toInt(),
        borderColor: Int = 0xFF00FF66.toInt()
    ) {
        val drawable = GradientDrawable().apply {
            setColor(bgColor)
            setStroke(1, borderColor)
            cornerRadius = 8f
        }
        background = drawable
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 16f
        setPadding(8, 16, 8, 16)
    }

    override fun onCreateInputView(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111111.toInt())
            setPadding(4, 4, 4, 4)
        }

        // ✅ Header dengan label dan tombol settings
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 8)
        }

        val label = TextView(this).apply {
            text = "⚡ KECOAXX IS FUCKIN RUNNING..."
            setTextColor(0xFF00FF66.toInt())
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val btnKeyboardSett = Button(this).apply {
            text = "⚙️"
            textSize = 18f
            setOnClickListener {
                try {
                    val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }.also { it.styleButton(bgColor = 0xFF333333.toInt()) }

        btnKeyboardSett.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(8, 0, 0, 0)
        }

        headerRow.addView(label)
        headerRow.addView(btnKeyboardSett)
        layout.addView(headerRow)

        // ✅ Baris 1: Angka 1-0 (10 tombol)
        val numberRow = createRow("1234567890", leftSpacing = 0f)
        layout.addView(numberRow)

        // ✅ Baris 2: QWERTYUIOP (10 tombol)
        val row1 = createRow("QWERTYUIOP", leftSpacing = 0f)
        layout.addView(row1)

        // ✅ Baris 3: ASDFGHJKL (9 tombol, mulai dari posisi 0.5 tombol)
        val row2 = createRow("ASDFGHJKL", leftSpacing = 0.5f)
        layout.addView(row2)

        // ✅ Baris 4: ZXCVBNM (7 tombol, mulai dari posisi 1.5 tombol)
        val row3 = createRow("ZXCVBNM", leftSpacing = 1.5f)
        layout.addView(row3)

        // ✅ Baris 5: Caps, Koma, Space, Titik, Del
        val controlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(4, 4, 4, 4)
        }

        val capsBtn = Button(this).apply {
            text = if (isCaps) "▼" else "▲"
            setOnClickListener {
                isCaps = !isCaps
                onCreateInputView()?.let { setInputView(it) }
            }
        }.also { it.styleButton(bgColor = 0xFF444444.toInt()) }

        val commaBtn = Button(this).apply {
            text = ","
            setOnClickListener {
                currentInputConnection?.commitText(",", 1)
            }
        }.also { it.styleButton() }

        val spaceBtn = Button(this).apply {
            text = "━━━"
            setOnClickListener {
                currentInputConnection?.commitText(" ", 1)
            }
        }.also { it.styleButton(bgColor = 0xFF444444.toInt()) }

        val dotBtn = Button(this).apply {
            text = "."
            setOnClickListener {
                currentInputConnection?.commitText(".", 1)
            }
        }.also { it.styleButton() }

        val delBtn = Button(this).apply {
            text = "⌫"
            setOnClickListener {
                currentInputConnection?.deleteSurroundingText(1, 0)
            }
        }.also { it.styleButton(bgColor = 0xFF444444.toInt()) }

        // ✅ Layout weight yang proporsional (mirip keyboard Android)
        capsBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)
        commaBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        spaceBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 4f)
        dotBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        delBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)

        controlRow.addView(capsBtn)
        controlRow.addView(commaBtn)
        controlRow.addView(spaceBtn)
        controlRow.addView(dotBtn)
        controlRow.addView(delBtn)
        layout.addView(controlRow)

        return layout
    }

    // ✅ Helper function dengan spacing untuk staggered layout
    private fun createRow(chars: String, leftSpacing: Float = 0f): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START // ✅ Align ke kiri, bukan center
            setPadding(4, 4, 4, 4)

            // ✅ Tambah spacing kiri (untuk efek staggered)
            if (leftSpacing > 0) {
                val spacer = Space(this@BotKeyboard).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        leftSpacing
                    )
                }
                addView(spacer)
            }

            // ✅ Tambah tombol
            for (char in chars) {
                val btn = Button(this@BotKeyboard).apply {
                    text = if (isCaps) char.uppercase() else char.lowercase()
                    setOnClickListener {
                        val inputChar = if (isCaps) char.uppercase() else char.lowercase()
                        currentInputConnection?.commitText(inputChar, 1)
                    }
                }.also { it.styleButton() }

                val params = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    setMargins(2, 2, 2, 2)
                }
                btn.layoutParams = params
                addView(btn)
            }

            // ✅ Tambah spacing kanan (untuk balance)
            if (leftSpacing > 0) {
                val spacer = Space(this@BotKeyboard).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        leftSpacing
                    )
                }
                addView(spacer)
            }
        }
    }

    /** Dipanggil dari service untuk ngetik otomatis */
    fun typeText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }
}