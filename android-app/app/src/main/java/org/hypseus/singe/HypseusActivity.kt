package org.hypseus.singe

import android.content.Context
import android.graphics.Color
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.libsdl.app.SDLActivity
import org.libsdl.app.SDLControllerManager

/**
 * Hosts the SDL rendering surface for Hypseus Singe.
 *
 * SDLActivity creates the Android SurfaceView that SDL needs before
 * SDL_CreateWindow() can succeed. It then launches the SDL thread and
 * calls SDL_main() (which is our hypseus_run()) via nativeRunMain().
 *
 * Game arguments are passed from MainActivity as an Intent string-array
 * extra ("args"). They map to argv[1..N] in SDL_main (argv[0] is the
 * library path supplied by nativeRunMain).
 */
class HypseusActivity : SDLActivity(), InputManager.InputDeviceListener {

    private val logTag = "HypseusInput"

    companion object {
        @Volatile
        private var sessionActive: Boolean = false

        fun isSessionActive(): Boolean = sessionActive

        fun requestSessionClose() {
            if (!sessionActive) return
            sessionActive = false
            runCatching { nativeSendQuit() }
        }
    }

    private lateinit var inputManager: InputManager
    private var virtualControlsOverlay: View? = null
    private var overlayInitialized = false
    private var lastControllerState: Boolean? = null
    private var leftPressed = false
    private var rightPressed = false
    private var upPressed = false
    private var downPressed = false
    private var isExiting = false

    /** Load only our single all-in-one library (SDL is statically linked). */
    override fun getLibraries(): Array<String> = arrayOf("hypseus")

    /** The entry point exported by hypseus.cpp for Android. */
    override fun getMainFunction(): String = "SDL_main"

    /**
     * Arguments after argv[0] (the library path).
     * Expected layout: ["lair", "vldp", "-framefile", ..., "-homedir", ..., "-datadir", ...]
     */
    override fun getArguments(): Array<String> =
        intent.getStringArrayExtra("args") ?: emptyArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionActive = true
        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        ensureVirtualControlsOverlay()
        updateControllerMode(showToast = false)
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionActive = false
        Runtime.getRuntime().gc()
    }

    override fun onResume() {
        super.onResume()
        inputManager.registerInputDeviceListener(this, null)
        updateControllerMode(showToast = false)
    }

    override fun onPause() {
        inputManager.unregisterInputDeviceListener(this)
        super.onPause()
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        updateControllerMode(showToast = true)
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        updateControllerMode(showToast = true)
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        updateControllerMode(showToast = true)
    }

    private fun updateControllerMode(showToast: Boolean) {
        val hasExternalController = hasAttachedGameController()
        val assistMode = intent.getBooleanExtra("assist_mode", false)
        virtualControlsOverlay?.visibility = if (hasExternalController && !assistMode) View.GONE else View.VISIBLE

        if (showToast && lastControllerState != hasExternalController) {
            val message = if (hasExternalController) {
                "External controller detected"
            } else {
                "No controller found, virtual controls enabled"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        lastControllerState = hasExternalController
    }

    private fun hasAttachedGameController(): Boolean {
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            if (device.isVirtual) continue

            val sources = device.sources
            val hasGamepadSource =
                (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                    (sources and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD

            if (hasGamepadSource) {
                return true
            }
        }
        return false
    }

    private fun ensureVirtualControlsOverlay() {
        if (overlayInitialized) return

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(16), dp(16), dp(16), dp(24))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val dpad = buildDpad()
        val actionButtons = buildActionButtons()
        val assistHint = buildAssistHint()

        val leftParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val rightParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            gravity = Gravity.END
        }

        root.addView(dpad, leftParams)
        if (intent.getBooleanExtra("assist_mode", false)) {
            root.addView(assistHint, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            })
        }
        root.addView(actionButtons, rightParams)

        addContentView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        virtualControlsOverlay = root
        overlayInitialized = true
    }

    private fun buildDpad(): View {
        val dpad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }

        val rowTop = LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val rowMiddle = LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val rowBottom = LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL }

        val up = buildControlButton("U", KeyEvent.KEYCODE_DPAD_UP)
        val left = buildControlButton("L", KeyEvent.KEYCODE_DPAD_LEFT)
        val right = buildControlButton("R", KeyEvent.KEYCODE_DPAD_RIGHT)
        val down = buildControlButton("D", KeyEvent.KEYCODE_DPAD_DOWN)

        rowTop.addView(up)
        rowMiddle.addView(left)
        rowMiddle.addView(spacer())
        rowMiddle.addView(right)
        rowBottom.addView(down)

        dpad.addView(rowTop)
        dpad.addView(rowMiddle)
        dpad.addView(rowBottom)
        return dpad
    }

    private fun buildActionButtons(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(buildActionButton("SWORD"))
            addView(buildControlButton("START", KeyEvent.KEYCODE_1))
            addView(buildControlButton("COIN", KeyEvent.KEYCODE_5))
            addView(buildExitButton("EXIT"))
        }
    }

    override fun onBackPressed() {
        exitToHypseusMenu()
    }

    private fun exitToHypseusMenu() {
        if (isExiting || isFinishing) return
        isExiting = true
        try {
            nativeSendQuit()
        } catch (error: UnsatisfiedLinkError) {
            Log.w(logTag, "nativeSendQuit unavailable while exiting", error)
        }
        sendVirtualKey(KeyEvent.KEYCODE_ESCAPE, true)
        sendVirtualKey(KeyEvent.KEYCODE_ESCAPE, false)
        if (!isFinishing) {
            finish()
        }
        window.decorView.postDelayed({ Runtime.getRuntime().gc() }, 500L)
    }

    private fun buildAssistHint(): View {
        return TextView(this).apply {
            text = "Assist: press COIN/Select, then START | D-pad moves | A/B/X/Y action"
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
    }

    private fun buildControlButton(label: String, keyCode: Int): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            alpha = 0.85f
            minWidth = dp(64)
            minHeight = dp(64)
            val lp = LinearLayout.LayoutParams(dp(64), dp(64)).apply {
                setMargins(dp(6), dp(6), dp(6), dp(6))
            }
            layoutParams = lp
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        sendVirtualKey(keyCode, true)
                        true
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL,
                    MotionEvent.ACTION_POINTER_UP -> {
                        sendVirtualKey(keyCode, false)
                        true
                    }

                    else -> false
                }
            }
        }
    }

    /**
     * Let SDL consume physical controller events first so controllerGetButton,
     * controllerGetAxis, and hypinput_gamepad.ini all see the complete pad.
     * The keyboard fallback remains for devices Android does not expose as SDL
     * controllers.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val source = event.source
        val padLikeSource =
            (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                (source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
        val isSinge = getArguments().firstOrNull()?.equals("singe", ignoreCase = true) == true
        if (padLikeSource) {
            Log.d(
                logTag,
                "dispatchKeyEvent keyCode=${event.keyCode} action=${event.action} source=$source deviceId=${event.deviceId}",
            )
        }

        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) {
                exitToHypseusMenu()
            }
            return true
        }

        if (padLikeSource && super.dispatchKeyEvent(event)) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> mirrorControllerKey(event, isSinge, true)
                KeyEvent.ACTION_UP -> mirrorControllerKey(event, isSinge, false)
            }
            return true
        }

        val remappedCode = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER ->
                KeyEvent.KEYCODE_CTRL_LEFT
            KeyEvent.KEYCODE_BUTTON_B ->
                KeyEvent.KEYCODE_ALT_LEFT
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y ->
                KeyEvent.KEYCODE_SPACE
            KeyEvent.KEYCODE_BUTTON_START ->
                KeyEvent.KEYCODE_1
            KeyEvent.KEYCODE_BUTTON_SELECT ->
                KeyEvent.KEYCODE_5
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_BUTTON_MODE,
            KeyEvent.KEYCODE_ESCAPE ->
                KeyEvent.KEYCODE_ESCAPE
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> event.keyCode
            else -> return super.dispatchKeyEvent(event)
        }

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (isSinge && event.isActionButtonForSinge()) {
                    sendActionKey(true)
                } else {
                    sendVirtualKey(remappedCode, true)
                }
                true
            }
            KeyEvent.ACTION_UP -> {
                if (isSinge && event.isActionButtonForSinge()) {
                    sendActionKey(false)
                } else {
                    sendVirtualKey(remappedCode, false)
                }
                true
            }
            else -> true
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_MOVE) {
            return super.onGenericMotionEvent(event)
        }

        val source = event.source
        val isPadLikeSource =
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD

        if (!isPadLikeSource) {
            return super.onGenericMotionEvent(event)
        }

        if (SDLControllerManager.handleJoystickMotionEvent(event)) {
            return true
        }

        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val stickX = event.getAxisValue(MotionEvent.AXIS_X)
        val stickY = event.getAxisValue(MotionEvent.AXIS_Y)

        val threshold = 0.5f

        val leftNow = (hatX <= -0.5f) || (hatX == 0f && stickX <= -threshold)
        val rightNow = (hatX >= 0.5f) || (hatX == 0f && stickX >= threshold)
        val upNow = (hatY <= -0.5f) || (hatY == 0f && stickY <= -threshold)
        val downNow = (hatY >= 0.5f) || (hatY == 0f && stickY >= threshold)

        if (leftNow != leftPressed) {
            sendVirtualKey(KeyEvent.KEYCODE_DPAD_LEFT, leftNow)
            leftPressed = leftNow
        }
        if (rightNow != rightPressed) {
            sendVirtualKey(KeyEvent.KEYCODE_DPAD_RIGHT, rightNow)
            rightPressed = rightNow
        }
        if (upNow != upPressed) {
            sendVirtualKey(KeyEvent.KEYCODE_DPAD_UP, upNow)
            upPressed = upNow
        }
        if (downNow != downPressed) {
            sendVirtualKey(KeyEvent.KEYCODE_DPAD_DOWN, downNow)
            downPressed = downNow
        }

        return true
    }

    private fun sendVirtualKey(keyCode: Int, down: Boolean) {
        val action = if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(
            now,
            now,
            action,
            keyCode,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            KeyEvent.FLAG_SOFT_KEYBOARD,
            InputDevice.SOURCE_KEYBOARD,
        )
        super.dispatchKeyEvent(event)
    }

    private fun sendActionKey(down: Boolean) {
        sendVirtualKey(KeyEvent.KEYCODE_SPACE, down)
        sendVirtualKey(KeyEvent.KEYCODE_CTRL_LEFT, down)
    }

    private fun mirrorControllerKey(event: KeyEvent, isSinge: Boolean, down: Boolean) {
        if (isSinge && event.isActionButtonForSinge()) {
            sendActionKey(down)
            return
        }
        val keyCode = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> KeyEvent.KEYCODE_CTRL_LEFT
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_ALT_LEFT
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_SPACE
            KeyEvent.KEYCODE_BUTTON_START -> KeyEvent.KEYCODE_1
            KeyEvent.KEYCODE_BUTTON_SELECT -> KeyEvent.KEYCODE_5
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> event.keyCode
            else -> return
        }
        sendVirtualKey(keyCode, down)
    }

    private fun KeyEvent.isActionButtonForSinge(): Boolean {
        return keyCode == KeyEvent.KEYCODE_BUTTON_A ||
            keyCode == KeyEvent.KEYCODE_BUTTON_B ||
            keyCode == KeyEvent.KEYCODE_BUTTON_X ||
            keyCode == KeyEvent.KEYCODE_BUTTON_Y ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
    }

    private fun buildActionButton(label: String): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            alpha = 0.85f
            minWidth = dp(64)
            minHeight = dp(64)
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply {
                setMargins(dp(6), dp(6), dp(6), dp(6))
            }
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        sendActionKey(true)
                        true
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL,
                    MotionEvent.ACTION_POINTER_UP -> {
                        sendActionKey(false)
                        true
                    }

                    else -> false
                }
            }
        }
    }

    private fun buildExitButton(label: String): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            alpha = 0.85f
            minWidth = dp(64)
            minHeight = dp(64)
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply {
                setMargins(dp(6), dp(6), dp(6), dp(6))
            }
            setOnClickListener { exitToHypseusMenu() }
        }
    }

    private fun spacer(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
