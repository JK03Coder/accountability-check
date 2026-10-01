package nz.co.accountabilitycheck.testtarget

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** A deliberately small, offline target for cross-app accessibility checks. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val destination = savedInstanceState?.getString("destination")
            ?: intent.data?.takeIf {
                it.scheme == "accountability-test" && it.host == "destination"
            }?.toString()
            ?: "Launcher entry"
        val content = fixtureLayout("Test target")
        content.addView(TextView(this).apply {
            text = destination
            contentDescription = "Test destination"
            textSize = 18f
            setPadding(0, 24, 0, 24)
        })
        content.addView(EditText(this).apply {
            id = 1001
            hint = "Type here to test the keyboard"
            contentDescription = "Target text field"
            setSingleLine(true)
            setText(savedInstanceState?.getString("typed_text").orEmpty())
        })
        content.addView(Button(this).apply {
            text = "Open second screen"
            isAllCaps = false
            setOnClickListener { startActivity(Intent(this@MainActivity, SecondActivity::class.java)) }
        })
        content.addView(TextView(this).apply {
            text = "Leave this screen open to test timer expiry without navigation."
            setPadding(0, 24, 0, 0)
        })
        setContentView(content)
        intent.putExtra("displayed_destination", destination)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("destination", intent.getStringExtra("displayed_destination"))
        outState.putString("typed_text", findViewById<EditText>(1001).text.toString())
        super.onSaveInstanceState(outState)
    }
}

class SecondActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(fixtureLayout("Second target screen").apply {
            addView(TextView(this@SecondActivity).apply {
                text = "This is another Activity in the same protected package."
                setPadding(0, 24, 0, 24)
            })
            addView(Button(this@SecondActivity).apply {
                text = "Return to first screen"
                isAllCaps = false
                setOnClickListener { finish() }
            })
        })
    }
}

private fun Activity.fixtureLayout(title: String) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    val padding = (24 * resources.displayMetrics.density).toInt()
    setPadding(padding, padding, padding, padding)
    // Respect status/navigation bars on target API 36, including gesture navigation.
    setOnApplyWindowInsetsListener { view, insets ->
        val bars = if (android.os.Build.VERSION.SDK_INT >= 30) {
            insets.getInsets(android.view.WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            android.graphics.Insets.of(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                insets.systemWindowInsetBottom,
            )
        }
        view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
        insets
    }
    addView(TextView(this@fixtureLayout).apply {
        text = title
        textSize = 26f
        isAccessibilityHeading = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    })
}
