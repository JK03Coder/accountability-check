package nz.co.accountabilitycheck.service

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowInsetsCompat
import nz.co.accountabilitycheck.R
import nz.co.accountabilitycheck.domain.ThemeMode

/** A real accessibility window; never starts an Activity over the target app. */
class OverlayController(private val service: AccessibilityService) {
    enum class Kind { PROMPT, REMINDER }
    private val windows = service.getSystemService(WindowManager::class.java)
    private var root: View? = null
    var key: String? = null; private set
    var kind: Kind? = null; private set
    var generation: Long = 0; private set
    val isShowing get() = root != null
    private var errorView: TextView? = null
    private var startButton: Button? = null
    private var ink = Color.rgb(25, 48, 44)
    private var green = Color.rgb(23, 107, 91)
    private var cream = Color.rgb(247, 246, 239)
    private var field = Color.WHITE
    private var hintColor = Color.DKGRAY
    private var secondary = Color.rgb(231, 238, 230)
    private var primaryText = Color.WHITE
    private var error = Color.rgb(158, 47, 34)

    fun showPrompt(
        scopeKey: String,
        appLabel: String,
        phone: Boolean,
        themeMode: ThemeMode,
        onStart: (String, Int) -> Unit,
        onCancel: () -> Unit,
        onPause: () -> Unit,
    ) {
        if (key == scopeKey && kind == Kind.PROMPT) return
        dismiss()
        applyTheme(themeMode)
        val body = column()
        body.addView(text("ACCOUNTABILITY CHECK", 12, green, bold = true))
        body.addView(text(if (phone) "A little intention goes a long way." else "BEFORE YOU OPEN ${appLabel.uppercase()}", 13))
        body.addView(text(if (phone) "What are you using your phone for?" else "What are you here to do?", 30, bold = true).apply { id = R.id.gate_title })
        body.addView(text("Name one thing you want to accomplish. Give it a little room, then get back to your day.", 16))
        val intention = EditText(service).apply {
            id = R.id.intention_input
            hint = "e.g. Upload to my story"
            contentDescription = "Your intention"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(InputFilter.LengthFilter(200))
            minLines = 2; maxLines = 4
            setTextColor(ink); setHintTextColor(hintColor)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(field)
        }
        body.addView(intention, margins())
        body.addView(text("How many minutes?", 16, bold = true))
        val duration = EditText(service).apply {
            id = R.id.duration_input
            hint = "Minutes"; contentDescription = "Minutes"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("5"); setSelectAllOnFocus(true)
            filters = arrayOf(InputFilter.LengthFilter(3))
            setTextColor(ink); setHintTextColor(hintColor); background = rounded(field)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        body.addView(duration, margins())
        val presets = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(2, 5, 10, 15).forEach { minutes ->
            presets.addView(button("$minutes min", false) { duration.setText(minutes.toString()) },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
        }
        body.addView(presets, margins())
        body.addView(text("Time keeps running if you switch apps or turn off your screen. Choose 1–120 minutes.", 13))
        errorView = text("", 14, error).also { body.addView(it) }
        startButton = button("Start session", true) {
            val purpose = intention.text.toString().trim()
            val minutes = duration.text.toString().toIntOrNull()
            when {
                purpose.isBlank() -> showError("Write a short intention first.")
                minutes == null || minutes !in 1..120 -> showError("Choose between 1 and 120 minutes.")
                else -> { startButton?.isEnabled = false; onStart(purpose, minutes) }
            }
        }.apply { id = R.id.start_session }
        body.addView(startButton, margins())
        if (!phone) body.addView(button("Go Home", false, onCancel).apply { id = R.id.go_home }, margins())
        body.addView(button("Pause protection", false, onPause).apply { id = R.id.pause_protection }, margins())
        attach(body, scopeKey, Kind.PROMPT, onCancel)
    }

    fun showReminder(sessionId: String, intention: String, minutes: Int, themeMode: ThemeMode, onDismiss: () -> Unit) {
        dismiss()
        applyTheme(themeMode)
        val body = column()
        body.addView(text("ACCOUNTABILITY CHECK", 12, green, bold = true))
        body.addView(text("Time to wrap up", 32, bold = true).apply { id = R.id.gate_title })
        body.addView(text("You planned to", 16))
        body.addView(text(intention, 24, bold = true))
        body.addView(text("Your $minutes minutes are up. Take a breath before you choose what comes next.", 17))
        body.addView(button("Dismiss", true, onDismiss), margins())
        attach(body, sessionId, Kind.REMINDER, onDismiss)
    }

    fun showError(message: String) { errorView?.text = message; startButton?.isEnabled = true }

    private fun applyTheme(mode: ThemeMode) {
        val systemDark = service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val dark = when (mode) {
            ThemeMode.SYSTEM -> systemDark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        ink = if (dark) Color.rgb(234, 243, 235) else Color.rgb(25, 48, 44)
        green = if (dark) Color.rgb(145, 214, 181) else Color.rgb(23, 107, 91)
        cream = if (dark) Color.rgb(17, 26, 22) else Color.rgb(247, 246, 239)
        field = if (dark) Color.rgb(35, 53, 43) else Color.WHITE
        hintColor = if (dark) Color.rgb(174, 190, 178) else Color.DKGRAY
        secondary = if (dark) Color.rgb(39, 61, 49) else Color.rgb(231, 238, 230)
        primaryText = if (dark) Color.rgb(16, 52, 39) else Color.WHITE
        error = if (dark) Color.rgb(255, 180, 168) else Color.rgb(158, 47, 34)
    }

    fun dismiss() {
        generation++
        val old = root
        root = null; key = null; kind = null; errorView = null; startButton = null
        if (old != null) {
            service.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(old.windowToken, 0)
            runCatching { windows.removeViewImmediate(old) }
        }
    }

    private fun attach(body: LinearLayout, scopeKey: String, type: Kind, onBack: () -> Unit) {
        val scroll = object : ScrollView(service) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    if (rootWindowInsets?.let {
                            WindowInsetsCompat.toWindowInsetsCompat(it, this).isVisible(WindowInsetsCompat.Type.ime())
                        } == true) {
                        service.getSystemService(InputMethodManager::class.java)
                            .hideSoftInputFromWindow(windowToken, 0)
                        return true
                    }
                    onBack(); return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(cream)
            isFillViewport = true
            addView(body)
            setPadding(dp(24), dp(28), dp(24), dp(24))
            setOnApplyWindowInsetsListener { view, insets ->
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                    view.setPadding(dp(24) + bars.left, dp(20) + bars.top, dp(24) + bars.right, dp(20) + bars.bottom)
                }
                insets
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            title = "Accountability Check"
        }
        windows.addView(scroll, params)
        root = scroll; key = scopeKey; kind = type
        scroll.requestApplyInsets()
    }

    private fun column() = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = FrameLayout.LayoutParams(-1, -2)
    }
    private fun text(value: String, size: Int, color: Int = ink, bold: Boolean = false) = TextView(service).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        setLineSpacing(dp(3).toFloat(), 1f)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        layoutParams = margins()
    }
    private fun button(label: String, primary: Boolean, action: () -> Unit) = Button(service).apply {
        text = label; isAllCaps = false; textSize = 16f; minHeight = dp(50)
        setTextColor(if (primary) primaryText else green)
        background = rounded(if (primary) green else secondary)
        setOnClickListener { action() }
    }
    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(16).toFloat() }
    private fun margins() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(8) }
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
}
