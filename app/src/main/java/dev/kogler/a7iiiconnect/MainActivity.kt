package dev.kogler.a7iiiconnect

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.nfc.NfcAdapter
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var store: Store
    private lateinit var setupPanel: LinearLayout
    private lateinit var syncPanel: LinearLayout
    private lateinit var ssidInput: EditText
    private lateinit var passInput: EditText
    private lateinit var cameraLabel: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var syncButton: Button
    private val listener: (SyncStatus) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "A7III Connect"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
        })

        // First-time setup: the network name and password the camera shows.
        setupPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setupPanel.addView(TextView(this).apply {
            text = "On the camera open MENU > Network > Send to Smartphone Func. > Send to Smartphone > " +
                "Select on Smartphone, then copy the network name and password it shows."
            setPadding(0, pad, 0, 0)
        })
        ssidInput = EditText(this).apply {
            hint = "Network name, e.g. DIRECT-A9E1:ILCE-7M3"
            setSingleLine()
            setText(store.ssid)
        }
        passInput = EditText(this).apply {
            hint = "Password"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(store.password)
        }
        setupPanel.addView(ssidInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setupPanel.addView(passInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setupPanel.addView(Button(this).apply {
            text = "Save camera"
            setOnClickListener {
                store.ssid = ssidInput.text.toString().trim()
                store.password = passInput.text.toString()
                showPanels()
            }
        })
        root.addView(setupPanel)

        // Everyday screen: one button.
        syncPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cameraLabel = TextView(this).apply { setPadding(0, pad / 2, 0, pad) }
        syncPanel.addView(cameraLabel)
        syncButton = Button(this).apply {
            textSize = 20f
            minHeight = (72 * dp).toInt()
            setOnClickListener {
                if (SyncState.status.running) SyncService.cancel(this@MainActivity)
                else SyncService.start(this@MainActivity)
            }
        }
        syncPanel.addView(syncButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        syncPanel.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad })
        statusText = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, pad / 2, 0, pad)
        }
        syncPanel.addView(statusText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        syncPanel.addView(android.widget.Switch(this).apply {
            text = "Smaller copies (much faster, good for sharing)"
            isChecked = store.smallCopies
            setOnCheckedChangeListener { _, on -> store.smallCopies = on }
            setPadding(0, 0, 0, pad / 2)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        syncPanel.addView(TextView(this).apply {
            text = "With this screen open, touch the phone to the camera's N mark. Or open Send to Smartphone on the camera and tap Get photos. Photos land in the " +
                "\"A7III Connect\" album, newest first, and copying continues with the screen off."
            alpha = 0.7f
        })

        val links = LinearLayout(this).apply { setPadding(0, pad, 0, 0) }
        links.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Change camera"
            setOnClickListener { setupPanel.visibility = View.VISIBLE }
        })
        links.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Share log"
            setOnClickListener {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, SyncState.logText())
                }, "Share log"))
            }
        })
        syncPanel.addView(links)
        root.addView(syncPanel)

        setContentView(root)
        showPanels()

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (savedInstanceState == null) handleNfc(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNfc(intent)
    }

    /** Phone touched the camera: save its Wi-Fi details and start copying straight away. */
    private fun handleNfc(intent: Intent?) {
        if (!NfcPairing.isNfcIntent(intent)) return
        onCameraTouched(NfcPairing.read(intent!!))
    }

    private fun onCameraTouched(creds: NfcPairing.Credentials?) {
        if (creds == null) {
            statusText.text = "Couldn't read the camera's NFC tag. Tap Share log and send it over."
            if (!store.hasCamera) return
        } else {
            store.ssid = creds.ssid
            store.password = creds.password
            ssidInput.setText(creds.ssid)
            passInput.setText(creds.password)
            showPanels()
        }
        if (!SyncState.status.running) SyncService.start(this)
    }

    // While the app is on screen it reads the camera's tag itself. Otherwise Android follows the
    // tag's pointer to Sony's Imaging Edge app and opens the Play Store.
    override fun onResume() {
        super.onResume()
        NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(
            this,
            { tag -> NfcPairing.read(tag).let { creds -> runOnUiThread { onCameraTouched(creds) } } },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V,
            null,
        )
    }

    override fun onPause() {
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        SyncState.observe(listener)
    }

    override fun onStop() {
        SyncState.remove(listener)
        super.onStop()
    }

    private fun showPanels() {
        setupPanel.visibility = if (store.hasCamera) View.GONE else View.VISIBLE
        syncPanel.visibility = if (store.hasCamera) View.VISIBLE else View.GONE
        cameraLabel.text = "Camera: ${store.ssid}"
    }

    private fun render(s: SyncStatus) {
        syncButton.text = if (s.running) "Stop" else "Get photos"
        statusText.text = s.message + (s.currentFile?.let { "\n$it" } ?: "")
        progress.visibility = if (s.running) View.VISIBLE else View.INVISIBLE
        progress.isIndeterminate = s.total == 0
        progress.max = s.total.coerceAtLeast(1)
        progress.progress = s.done
    }
}
