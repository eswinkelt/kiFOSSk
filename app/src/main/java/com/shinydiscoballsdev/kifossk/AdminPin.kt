package com.shinydiscoballsdev.kifossk

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Admin-PIN für den Zugang zu den Einstellungen. Die PIN ist eine reine App-PIN und
 * unabhängig von der Android-Gerätesperre, damit das Tablet nach einem Neustart
 * weiterhin direkt auf dem Board startet.
 */
object AdminPin {
    private const val MIN_LENGTH = 4
    private const val MAX_LENGTH = 8

    fun showSetPin(
        activity: AppCompatActivity,
        title: String = "Admin-PIN festlegen",
        message: String = "Schützt die Einstellungen. 4 bis 8 Ziffern, die nur die Eltern kennen.",
        onSuccess: () -> Unit
    ) {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 24), dp(activity, 8), dp(activity, 24), 0)
        }
        val first = pinField(activity, "PIN (4–8 Ziffern)")
        val second = pinField(activity, "PIN wiederholen")
        layout.addView(first)
        layout.addView(second)

        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setView(layout)
            .setPositiveButton("Speichern", null)
            .setNegativeButton("Abbrechen", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val a = first.text.toString()
                val b = second.text.toString()
                when {
                    a.length !in MIN_LENGTH..MAX_LENGTH || !a.all { it.isDigit() } -> {
                        first.error = "Bitte 4 bis 8 Ziffern eingeben"
                    }
                    a != b -> {
                        second.error = "Die PINs stimmen nicht überein"
                    }
                    else -> {
                        KioskPrefs.setAdminPin(activity, a)
                        dialog.dismiss()
                        onSuccess()
                    }
                }
            }
        }
        dialog.show()
        first.requestFocus()
    }

    fun showEnterPin(activity: AppCompatActivity, onSuccess: () -> Unit) {
        val input = pinField(activity, "Admin-PIN")
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 24), dp(activity, 8), dp(activity, 24), 0)
        }
        layout.addView(input)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Admin-PIN")
            .setMessage("Einstellungen öffnen")
            .setView(layout)
            .setPositiveButton("Öffnen", null)
            .setNegativeButton("Abbrechen", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (KioskPrefs.checkAdminPin(activity, input.text.toString())) {
                    dialog.dismiss()
                    onSuccess()
                } else {
                    input.error = "Falscher PIN"
                    input.text.clear()
                }
            }
        }
        dialog.show()
        input.requestFocus()
    }

    private fun pinField(activity: Context, hintText: String): EditText =
        EditText(activity).apply {
            hint = hintText
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            isSingleLine = true
            minHeight = dp(activity, 48)
        }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
