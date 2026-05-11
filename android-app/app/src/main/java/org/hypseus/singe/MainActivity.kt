package org.hypseus.singe

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private val lastLaunchRealtimeMs = AtomicLong(0)

    override fun onResume() {
        super.onResume()
        lastLaunchRealtimeMs.set(0L)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val activityContext = this

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val rootDir = remember { getPreferredDefaultRootDir() }
                    val prefs = remember { activityContext.getSharedPreferences("hypseus_prefs", Context.MODE_PRIVATE) }
                    remember { migrateDragonLairGameEntries(prefs, rootDir) }
                    val wizardResetOnThisInstall = remember { resetWizardForNewInstall(prefs) }

                    val defaultGameName = remember { guessDefaultGameName(rootDir) }
                    val lockedGameName = remember { lockedGameNameOrNull() }
                    val singleFolderMode = remember { lockedGameName != null }
                    var gameName by remember {
                        val initialGameName = if (lockedGameName != null) {
                            lockedGameName
                        } else {
                            val savedGameName = normalizeLaunchGameName(
                                prefs.getString("selected_game_name", prefs.getString("game_name", defaultGameName) ?: defaultGameName)
                                    ?: defaultGameName
                            )
                            if (savedGameName == "lair" || savedGameName == "singe") {
                                "dle"
                            } else {
                                savedGameName
                            }
                        }
                        mutableStateOf(
                            initialGameName
                        )
                    }
                    var gameNameExpanded by remember { mutableStateOf(false) }
                    var showGameSettings by remember { mutableStateOf(false) }
                    var selectedTab by remember { mutableStateOf(0) }  // 0=Launcher, 1=Logs
                    var pickerTarget by remember { mutableStateOf(PickerTarget.BASE_FOLDER) }

                    var lairFolderPath by remember { mutableStateOf(rootDir) }
                    var framefilePath by remember { 
                        mutableStateOf(
                            guessDefaultFramefile(rootDir, gameName.trim().lowercase())
                        )
                    }
                    var romDirPath by remember { mutableStateOf(rootDir) }
                    var singeScriptPath by remember { mutableStateOf(guessDefaultSingeScript(rootDir)) }
                    var digitalScoreboard by remember { mutableStateOf(false) }
                    var assistMode by remember { mutableStateOf(false) }
                    var audioDelayMs by remember { mutableStateOf("") }  // Audio delay for Singe 2 sync fix (milliseconds)
                    var dipBankA by remember { mutableStateOf("") }
                    var dipBankB by remember { mutableStateOf("") }
                    var launchDebugReport by remember {
                        mutableStateOf(prefs.getString("launch_debug_report", "") ?: "")
                    }
                    var setupValidationMessage by remember { mutableStateOf("") }
                    var configuredGameNames by remember {
                        mutableStateOf(loadConfiguredGames(prefs, gameName, lockedGameName))
                    }

                    val running = remember { mutableStateOf(false) }
                    var hasFullStorageAccess by remember { mutableStateOf(false) }
                    var hasMediaReadAccess by remember { mutableStateOf(hasMediaReadAccess()) }

                    // Wizard: determine initial step
                    // On fresh install, ALWAYS start with NEED_MEDIA_PERM even if granted (to reset persisted URIs)
                    val wizardDone = remember { prefs.getBoolean("wizard_done", false) }
                    var wizardStep by remember {
                        val step = when {
                            BuildConfig.LOCK_GAME_SELECTION -> WizardStep.DONE
                            wizardResetOnThisInstall -> {
                                // Fresh install: reset wizard to start from media permission
                                WizardStep.NEED_MEDIA_PERM
                            }
                            wizardDone -> WizardStep.DONE
                            !hasMediaReadAccess() -> WizardStep.NEED_MEDIA_PERM
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager() -> WizardStep.NEED_ALL_FILES
                            else -> WizardStep.SETUP_GAME
                        }
                        mutableStateOf(step)
                    }

                    val launchGame: () -> Unit = launch@{
                        Log.d("HypseusMain", "===== LAUNCH BUTTON TAPPED =====")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                            Toast.makeText(activityContext, "All Files Access is required. Tap the red button to grant it.", Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        if (!hasMediaReadAccess) {
                            Toast.makeText(activityContext, "Grant media read access first", Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        val now = System.currentTimeMillis()
                        val previousLaunch = lastLaunchRealtimeMs.get()
                        if (now - previousLaunch < 1500L) {
                            Toast.makeText(activityContext, "Please wait before launching again", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        lastLaunchRealtimeMs.set(now)
                        val relaunchDelayMs = if (HypseusActivity.isSessionActive()) {
                            Toast.makeText(
                                activityContext,
                                "Closing previous game before launch.",
                                Toast.LENGTH_SHORT
                            ).show()
                            HypseusActivity.requestSessionClose()
                            lastLaunchRealtimeMs.set(0L)
                            1200L
                        } else {
                            0L
                        }
                        running.value = true
                        val capturedGameName = gameName
                        val capturedBaseFolder = lairFolderPath
                        val capturedFramefile = framefilePath
                        val capturedRomDir = romDirPath
                        val capturedSinge = singeScriptPath
                        val capturedScoreboard = digitalScoreboard
                        val capturedAssistMode = assistMode
                        val capturedDipBankA = dipBankA
                        val capturedDipBankB = dipBankB
                        val capturedAudioDelayMs = audioDelayMs
                        Thread {
                            if (relaunchDelayMs > 0L) {
                                Thread.sleep(relaunchDelayMs)
                            }
                            val homeDir = filesDir.absolutePath
                            val requestedGame = normalizeLaunchGameName(capturedGameName)
                            
                            // Restore all persisted SAF URI permissions for this game
                            // SAF permissions don't auto-restore; must be explicitly restored at access time
                            listOf(
                                PickerTarget.BASE_FOLDER,
                                PickerTarget.ROM_FOLDER,
                                PickerTarget.FRAMEFILE_FILE,
                                PickerTarget.SINGE_SCRIPT_FILE
                            ).forEach { target ->
                                val uriString = prefs.getString(pickerUriPrefKey(requestedGame, target), null)
                                if (!uriString.isNullOrBlank()) {
                                    try {
                                        val uri = Uri.parse(uriString)
                                        val flags = if (target == PickerTarget.FRAMEFILE_FILE || target == PickerTarget.SINGE_SCRIPT_FILE) {
                                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        } else {
                                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                        }
                                        contentResolver.takePersistableUriPermission(uri, flags)
                                        Log.d("HypseusMain", "Restored SAF permission for $target: $uri")
                                    } catch (e: Exception) {
                                        Log.w("HypseusMain", "Could not restore SAF permission for $target: ${e.message}")
                                    }
                                }
                            }
                            val launchBaseFolder = normalizeBaseFolderSelection(capturedBaseFolder, requestedGame)
                            var launchFramefile = capturedFramefile
                            val launchRomDir = capturedRomDir
                            var launchSinge = capturedSinge
                            val lockedGameId = BuildConfig.LOCKED_GAME_ID
                            val lockedAce = lockedGameId.equals("ace", ignoreCase = true)
                            
                                // Locked game flavors should launch their game if external storage is configured.
                                val launchGameName = if (lockedGameId.isNotEmpty()) {
                                    Log.d("HypseusMain", "Locked to game: $lockedGameId")
                                    lockedGameId
                            } else if (isSingeGame(requestedGame) || hasLooseSingeScript(launchBaseFolder)) {
                                "singe"
                            } else {
                                requestedGame
                            }

                            if (launchGameName == "singe" && (launchSinge.isBlank() || !File(launchSinge).isFile)) {
                                val recovered = guessDefaultSingeScript(launchBaseFolder)
                                if (recovered.isNotBlank() && File(recovered).isFile) {
                                    launchSinge = recovered
                                    Log.i("HypseusMain", "Recovered missing singe script to: $launchSinge")
                                }
                            }

                            Log.d("HypseusMain", "About to call detectGameLayout: game=$launchGameName, baseFolder=$launchBaseFolder, framefile=$launchFramefile")
                            val detectedLayout = detectGameLayout(
                                game = launchGameName,
                                baseFolder = launchBaseFolder,
                                requestedFramefile = launchFramefile,
                                requestedRomDir = launchRomDir,
                                requestedSingeScript = launchSinge,
                            )
                            var effectiveFramefile = detectedLayout.framefile
                            val resolvedRomDir = detectedLayout.romDir
                            launchSinge = detectedLayout.singeScript

                            if (launchGameName == "singe" && (launchSinge.isBlank() || !File(launchSinge).isFile)) {
                                runOnUiThread {
                                    Toast.makeText(
                                        activityContext,
                                        "Singe script not found. Select a valid .singe/.zip script or switch to lair.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    running.value = false
                                }
                                return@Thread
                            }

                            val game = launchGameName

                            val cleanup = performPreLaunchCleanup(activityContext)
                            if (cleanup.lowStorageCritical) {
                                val freeMb = cleanup.freeInternalBytes / (1024 * 1024)
                                runOnUiThread {
                                    Toast.makeText(
                                        activityContext,
                                        "Not enough free internal storage ($freeMb MB). Free space and try again.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    running.value = false
                                }
                                return@Thread
                            }

                            if (effectiveFramefile.isBlank() || !File(effectiveFramefile).isFile) {
                                runOnUiThread {
                                    Toast.makeText(
                                        activityContext,
                                        "Framefile not found: $effectiveFramefile",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    running.value = false
                                }
                                return@Thread
                            }

                            if (game == "lair" || game == "laireuro") {
                                effectiveFramefile = prepareLairFramefile(
                                    originalFramefile = effectiveFramefile,
                                    baseFolder = launchBaseFolder,
                                )
                            } else if (isSingeGame(game) || game == "singe") {
                                effectiveFramefile = prepareGameFolderFramefile(
                                    originalFramefile = effectiveFramefile,
                                    baseFolder = launchBaseFolder,
                                    suffix = game,
                                )
                            }

                            val preflightValidation = validateRequiredLaunchFiles(
                                game = game,
                                baseFolder = launchBaseFolder,
                                framefile = effectiveFramefile,
                                romDir = resolvedRomDir,
                                singeScript = launchSinge,
                            )
                            if (!preflightValidation.ok) {
                                runOnUiThread {
                                    launchDebugReport = "Preflight failed:\n${preflightValidation.message}"
                                    prefs.edit().putString("launch_debug_report", launchDebugReport).apply()
                                    selectedTab = 1
                                    Toast.makeText(
                                        activityContext,
                                        "Launch blocked: required files are missing. Check Launch Debug Log for details.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    running.value = false
                                }
                                return@Thread
                            }

                            // Convert absolute framefile path to relative (hypseus expects relative to homedir)
                            val framefileRelative = try {
                                val absFramefile = File(effectiveFramefile).canonicalPath
                                val absHomedir = File(homeDir).canonicalPath
                                if (absFramefile.startsWith(absHomedir)) {
                                    absFramefile.substring(absHomedir.length).trimStart('/')
                                } else {
                                    effectiveFramefile // fall back if not under homedir
                                }
                            } catch (e: Exception) {
                                effectiveFramefile
                            }
                            launchDebugReport = "$launchDebugReport\n\n=== LAUNCH CONFIG ===\nGame: $game (locked to: ${BuildConfig.LOCKED_GAME_ID})\nFramefile absolute: $effectiveFramefile\nFramefile relative: $framefileRelative\nROM dir: $resolvedRomDir\nHomeDir: $homeDir"

                            // Detect if Space Ace should launch as singe (with SAe.singe script)
                            var aceUseSinge = false
                            var aceSingeScriptPath = ""
                            var aceSingeDir = launchBaseFolder

                            if (lockedAce && game == "singe" && launchSinge.isNotBlank() && File(launchSinge).isFile) {
                                aceUseSinge = true
                                aceSingeScriptPath = launchSinge
                                aceSingeDir = File(launchSinge).parentFile?.absolutePath ?: launchBaseFolder
                            }
                            
                            Log.d("HypseusMain", "Launch flow: game=$game, lockedAce=$lockedAce, launchBaseFolder=$launchBaseFolder")
                            if (game == "ace") {
                                Log.d("HypseusMain", "Checking for SAe singe script in: $launchBaseFolder")
                                val aceSingeScript = findSpaceAceSingeScript(launchBaseFolder)
                                if (aceSingeScript != null) {
                                    Log.d("HypseusMain", "Found SAe singe script: ${aceSingeScript.absolutePath}")
                                    val patchedSinge = prepareSingeScriptForAndroid(aceSingeScript.absolutePath)
                                    if (patchedSinge != null) {
                                        Log.d("HypseusMain", "Successfully patched SAe singe: ${patchedSinge.scriptPath}")
                                        aceSingeScriptPath = patchedSinge.scriptPath
                                        aceSingeDir = patchedSinge.singeDir
                                        aceUseSinge = true
                                        launchDebugReport = "$launchDebugReport\nLaunching as SINGE with SAe.singe script"
                                        launchDebugReport = "$launchDebugReport\nPatched SAe.singe paths to BASEDIR/MYDIR"
                                    } else {
                                        Log.w("HypseusMain", "Failed to patch SAe singe script")
                                    }
                                } else {
                                    Log.d("HypseusMain", "No SAe.singe script found. Trying alternative paths...")
                                    // Log candidate paths for debugging
                                    listOf(
                                        File(launchBaseFolder, "SAe/SAe.singe"),
                                        File(launchBaseFolder, "SAe/sae.singe"),
                                        File(launchBaseFolder, "sae.singe"),
                                        File(launchBaseFolder, "spaceace/SAe.singe"),
                                        File(launchBaseFolder, "spaceace/sae.singe")
                                    ).forEachIndexed { idx, f ->
                                        Log.d("HypseusMain", "  Candidate[$idx]: ${f.absolutePath} -> exists=${f.isFile}")
                                    }
                                }
                            } else {
                                Log.d("HypseusMain", "Skipping SAe detection: game != 'ace' (game=$game)")
                            }
                            
                            // All Singe-family games must launch via "singe" with an explicit script path.
                            val launchGame = if (aceUseSinge || isSingeGame(game) || game == "singe") "singe" else game
                            val args = mutableListOf(
                                launchGame, "vldp",
                                "-framefile", framefileRelative,
                                "-fullscreen",
                                "-homedir", homeDir,
                                "-datadir", homeDir
                            )
                            args.add("-gamepad")
                            if (launchGame != "lair" && launchGame != "laireuro") {
                                args.add("-opengl")
                            }
                            if (launchGame == "lair" || launchGame == "laireuro") {
                                args.add("-fastboot")
                                args.add("-teardown_window")
                            }
                            if (launchGame != "singe") {
                                args.add("-sram_continuous_update")
                            }
                            if (resolvedRomDir.isNotEmpty() && launchGame != "singe") {
                                // Hypseus appends the game name to romdir when searching for ROMs
                                // (e.g. romdir/lair/ or romdir/lair.zip). If the user already
                                // selected the game-named subfolder, step up one level automatically.
                                val effectiveRomDir = if (game == "ace" && !aceUseSinge) {
                                    prepareAceRomDirAlias(resolvedRomDir, launchBaseFolder)
                                } else {
                                    resolvedRomDir
                                }
                                args.add("-romdir"); args.add(effectiveRomDir)
                            }
                            var launchSingeDir = launchBaseFolder
                            if (launchGame == "singe" && launchSinge.isNotEmpty() && !launchSinge.endsWith(".zip", ignoreCase = true)) {
                                val patchedLaunch = prepareSingeScriptForAndroid(launchSinge)
                                if (patchedLaunch != null) {
                                    launchSinge = patchedLaunch.scriptPath
                                    launchSingeDir = patchedLaunch.singeDir
                                    if (aceUseSinge) {
                                        // Keep forced SAe Singe launch aligned with the patched script.
                                        aceSingeScriptPath = patchedLaunch.scriptPath
                                        aceSingeDir = patchedLaunch.singeDir
                                    }
                                }
                            }

                            if ((launchGame == "singe" && launchSinge.isNotEmpty()) || aceUseSinge) {
                                val singeScript = if (aceUseSinge) aceSingeScriptPath else launchSinge
                                if (!File(singeScript).isFile) {
                                    runOnUiThread {
                                        Toast.makeText(
                                            activityContext,
                                            "Singe script not found: $singeScript",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        running.value = false
                                    }
                                    return@Thread
                                }
                                if (singeScript.endsWith(".zip", ignoreCase = true)) args.add("-zlua")
                                else args.add("-script")
                                args.add(singeScript)

                                // For SAe (Space Ace Enhanced), pass -singedir pointing to the
                                // parent of the game folder on the SD card.  The Singe engine uses
                                // this as a prefix when resolving "singe/..." asset paths inside
                                // the Lua scripts (e.g. singe/SAe/Sounds/right.wav).
                                if (aceUseSinge) {
                                    val singeRootDir = File(aceSingeDir).parentFile?.absolutePath
                                    if (singeRootDir != null) {
                                        args.add("-singedir")
                                        args.add(singeRootDir)
                                        Log.d("HypseusMain", "SAe singedir: $singeRootDir")
                                    }
                                }
                            }
                            if (capturedScoreboard && (launchGame == "lair" || launchGame == "ace" || launchGame == "tq")) {
                                args.add("-scorebezel")
                            }
                            if (capturedAssistMode) {
                                args.add("-cheat")
                            }
                            val effectiveAudioDelayMs = when {
                                capturedAudioDelayMs.isNotBlank() -> capturedAudioDelayMs
                                BuildConfig.LOCKED_GAME_ID.equals("ace", ignoreCase = true) -> "0"
                                else -> ""
                            }

                            if (effectiveAudioDelayMs.isNotEmpty()) {
                                try {
                                    val delayMs = effectiveAudioDelayMs.toInt()
                                    if (delayMs > 0) {
                                        args.add("-audio-delay")
                                        args.add(delayMs.toString())
                                        Log.d("HypseusMain", "Audio delay: $delayMs ms")
                                    }
                                } catch (e: NumberFormatException) {
                                    Log.w("HypseusMain", "Invalid audio delay value: ${effectiveAudioDelayMs}")
                                }
                            }
                            addDipBankArgs(args, 0, capturedDipBankA)
                            addDipBankArgs(args, 1, capturedDipBankB)

                            val storageAccessReport = buildStorageAccessReport(
                                prefs = prefs,
                                game = game,
                                baseFolder = launchBaseFolder,
                                framefile = effectiveFramefile,
                                romDir = resolvedRomDir,
                                singeScript = launchSinge,
                            )

                            val launchReport = buildLaunchDebugReport(
                                game = game,
                                requestedBaseFolder = capturedBaseFolder,
                                requestedFramefile = capturedFramefile,
                                requestedRomDir = capturedRomDir,
                                requestedSingeScript = capturedSinge,
                                resolvedBaseFolder = launchBaseFolder,
                                resolvedFramefile = effectiveFramefile,
                                resolvedRomDir = resolvedRomDir,
                                resolvedSingeScript = launchSinge,
                                args = args,
                                storageAccessReport = storageAccessReport,
                            )

                            // Sync framework files from external storage to internal storage
                            // so native engine can find them via relative paths
                            syncFrameworkFilesFromExternalStorage(launchBaseFolder)

                            runOnUiThread {
                                val freedMb = cleanup.bytesFreed / (1024 * 1024)
                                val freeMb = cleanup.freeInternalBytes / (1024 * 1024)
                                launchDebugReport = "$launchReport\nPreflight cleanup: freed ${freedMb}MB, free internal ${freeMb}MB"
                                prefs.edit().putString("launch_debug_report", launchDebugReport).apply()
                                val intent = Intent(activityContext, HypseusActivity::class.java)
                                intent.putExtra("args", args.toTypedArray())
                                intent.putExtra("assist_mode", capturedAssistMode)
                                startActivity(intent)
                                running.value = false
                            }
                        }.start()
                    }

                    LaunchedEffect(gameName) {
                        if (lockedGameName != null && gameName != lockedGameName) {
                            gameName = lockedGameName
                            return@LaunchedEffect
                        }
                        val game = gameName.trim().lowercase()
                        val settings = loadGameSettings(prefs, game, rootDir)
                        lairFolderPath = settings.baseFolderPath
                        framefilePath = settings.framefilePath
                        romDirPath = settings.romDirPath
                        singeScriptPath = settings.singeScriptPath
                        digitalScoreboard = settings.digitalScoreboard
                        assistMode = settings.assistMode
                        dipBankA = settings.dipBankA
                        dipBankB = settings.dipBankB
                        audioDelayMs = settings.audioDelayMs
                        setupValidationMessage = ""
                    }

                    val lifecycleOwner = LocalLifecycleOwner.current
                    DisposableEffect(lifecycleOwner) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                val allFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                                    Environment.isExternalStorageManager() else true
                                hasFullStorageAccess = allFiles
                                hasMediaReadAccess = hasMediaReadAccess()
                                // Advance wizard when returning from Settings after granting All Files
                                if (wizardStep == WizardStep.NEED_ALL_FILES && allFiles) {
                                    wizardStep = if (!prefs.getBoolean("wizard_done", false))
                                        WizardStep.SETUP_GAME else WizardStep.DONE
                                }
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                    }

                    val mediaPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) {
                        hasMediaReadAccess = hasMediaReadAccess()
                        if (wizardStep == WizardStep.NEED_MEDIA_PERM) {
                            wizardStep = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                                WizardStep.NEED_ALL_FILES
                            } else if (!prefs.getBoolean("wizard_done", false)) {
                                WizardStep.SETUP_GAME
                            } else {
                                WizardStep.DONE
                            }
                        }
                    }

                    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                        uri?.let {
                            Log.d("HypseusMain", "Folder picker returned URI: $it")
                            // Must explicitly restore persistable permissions immediately after picker result
                            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            try {
                                contentResolver.takePersistableUriPermission(it, flags)
                                Log.d("HypseusMain", "Persisted SAF permission for $it")
                            } catch (e: Exception) {
                                Log.e("HypseusMain", "Failed to persist SAF permission: ${e.message}")
                            }
                            
                            val path = getPathFromUri(activityContext, it)
                            Log.d("HypseusMain", "Resolved URI to path: $path")
                            if (path != null) {
                                val game = gameName.trim().lowercase()
                                savePickedUri(prefs, game, pickerTarget, it)
                                when (pickerTarget) {
                                    PickerTarget.BASE_FOLDER -> {
                                        val normalizedBase = normalizeBaseFolderSelection(path, game)
                                        lairFolderPath = normalizedBase
                                        Log.d("HypseusMain", "Set lairFolderPath to: $normalizedBase (picked: $path)")
                                        val detectedLayout = detectGameLayout(
                                            game = game,
                                            baseFolder = normalizedBase,
                                            requestedFramefile = framefilePath,
                                            requestedRomDir = romDirPath,
                                            requestedSingeScript = singeScriptPath,
                                        )
                                        framefilePath = detectedLayout.framefile
                                        romDirPath = detectedLayout.romDir
                                        singeScriptPath = detectedLayout.singeScript
                                    }
                                    PickerTarget.ROM_FOLDER -> {
                                        romDirPath = path
                                    }
                                    else -> Unit
                                }
                            } else {
                                Toast.makeText(activityContext, "Could not resolve path. Try a location on primary storage.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }

                    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        uri?.let {
                            // Must explicitly restore persistable permissions immediately after picker result
                            try {
                                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                Log.d("HypseusMain", "Persisted SAF permission for $it")
                            } catch (e: Exception) {
                                Log.e("HypseusMain", "Failed to persist SAF permission: ${e.message}")
                            }
                            
                            val path = getPathFromUri(activityContext, it)
                            if (path != null) {
                                savePickedUri(prefs, gameName.trim().lowercase(), pickerTarget, it)
                                when (pickerTarget) {
                                    PickerTarget.FRAMEFILE_FILE -> framefilePath = path
                                    PickerTarget.SINGE_SCRIPT_FILE -> singeScriptPath = path
                                    else -> Unit
                                }
                            } else {
                                Toast.makeText(activityContext, "Could not resolve selected file path. Use manual path if needed.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        when (wizardStep) {
                            WizardStep.NEED_MEDIA_PERM -> {
                                Spacer(modifier = Modifier.height(24.dp))
                                Text("First Time Setup  (1 / 3)", style = MaterialTheme.typography.headlineSmall)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    "Hypseus needs permission to read media files (video, audio, images) from your device storage.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = { mediaPermissionLauncher.launch(requiredMediaPermissions()) },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("Grant Media Access")
                                }
                                if (hasMediaReadAccess) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = {
                                            wizardStep = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                                                WizardStep.NEED_ALL_FILES
                                            } else {
                                                WizardStep.SETUP_GAME
                                            }
                                        }
                                    ) {
                                        Text("Continue")
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "If Android doesn't show a dialog, go to:\nSettings → Apps → Hypseus Android → Permissions → Media and grant all three.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            WizardStep.NEED_ALL_FILES -> {
                                Spacer(modifier = Modifier.height(24.dp))
                                Text("First Time Setup  (2 / 3)", style = MaterialTheme.typography.headlineSmall)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    "Hypseus needs All Files Access to read game files from your SD card.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = {
                                        try {
                                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                            intent.data = Uri.parse("package:${activityContext.packageName}")
                                            activityContext.startActivity(intent)
                                        } catch (_: Exception) {
                                            activityContext.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("Open Settings  →  Grant All Files Access")
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Tap the button above, find Hypseus Android in the list, and enable the toggle. Then press Back to return here.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            WizardStep.SETUP_GAME -> {
                                Spacer(modifier = Modifier.height(24.dp))
                                Text(
                                    if (prefs.getBoolean("wizard_done", false)) "Game Setup" else "First Time Setup  (3 / 3)",
                                    style = MaterialTheme.typography.headlineSmall
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    "Choose your game and select the files it needs from your SD card.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                val gameNames = availableGameNames(lockedGameName)
                                if (lockedGameName == null) {
                                    ExposedDropdownMenuBox(
                                        expanded = gameNameExpanded,
                                        onExpandedChange = { gameNameExpanded = it },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        OutlinedTextField(
                                            value = gameDisplayName(gameName),
                                            onValueChange = {},
                                            label = { Text("Game") },
                                            singleLine = true,
                                            readOnly = true,
                                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = gameNameExpanded) },
                                            modifier = Modifier.menuAnchor().fillMaxWidth()
                                        )
                                        ExposedDropdownMenu(expanded = gameNameExpanded, onDismissRequest = { gameNameExpanded = false }) {
                                            gameNames.forEach { name ->
                                                DropdownMenuItem(
                                                    text = { Text(gameDisplayName(name)) },
                                                    onClick = {
                                                        gameName = name
                                                        gameNameExpanded = false
                                                        setupValidationMessage = ""
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = lairFolderPath,
                                    onValueChange = { lairFolderPath = it },
                                    label = { Text("Game Folder") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Button(
                                    onClick = {
                                        pickerTarget = PickerTarget.BASE_FOLDER
                                        folderLauncher.launch(null)
                                    }
                                ) {
                                    Text("Pick Game Folder")
                                }

                                val wizardGame = gameName.trim().lowercase()
                                if (!singleFolderMode) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    OutlinedTextField(
                                        value = framefilePath,
                                        onValueChange = { framefilePath = it },
                                        label = { Text("Framefile (.txt)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Button(onClick = {
                                        pickerTarget = PickerTarget.FRAMEFILE_FILE
                                        fileLauncher.launch(arrayOf("text/plain", "*/*"))
                                    }) {
                                        Text("Pick Framefile")
                                    }

                                    if (isSingeGame(wizardGame)) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        OutlinedTextField(
                                            value = singeScriptPath,
                                            onValueChange = { singeScriptPath = it },
                                            label = { Text("Singe Script (.singe or .zip)") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Button(onClick = {
                                            pickerTarget = PickerTarget.SINGE_SCRIPT_FILE
                                            fileLauncher.launch(arrayOf("application/zip", "text/plain", "*/*"))
                                        }) {
                                            Text("Pick Singe Script")
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        OutlinedTextField(
                                            value = romDirPath,
                                            onValueChange = { romDirPath = it },
                                            label = { Text("ROM Folder") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Button(onClick = {
                                            pickerTarget = PickerTarget.ROM_FOLDER
                                            folderLauncher.launch(null)
                                        }) {
                                            Text("Pick ROM Folder")
                                        }
                                    }
                                } else {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "One-folder mode: framefile and ROMs are auto-detected from the selected game folder.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                if (setupValidationMessage.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        setupValidationMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Red,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = {
                                        val game = gameName.trim().lowercase()
                                        val validation = validateRequiredLaunchFiles(
                                            game = game,
                                            baseFolder = lairFolderPath,
                                            framefile = framefilePath,
                                            romDir = romDirPath,
                                            singeScript = singeScriptPath,
                                        )
                                        if (!validation.ok) {
                                            setupValidationMessage = validation.message
                                            Toast.makeText(activityContext, "Some required files are missing", Toast.LENGTH_LONG).show()
                                            return@Button
                                        }
                                        saveGameSettings(
                                            prefs = prefs,
                                            game = game,
                                            baseFolderPath = lairFolderPath,
                                            framefilePath = framefilePath,
                                            romDirPath = romDirPath,
                                            singeScriptPath = singeScriptPath,
                                            digitalScoreboard = digitalScoreboard,
                                            assistMode = assistMode,
                                            dipBankA = dipBankA,
                                            dipBankB = dipBankB,
                                        )
                                        prefs.edit().putBoolean("wizard_done", true).apply()
                                        configuredGameNames = loadConfiguredGames(prefs, game, lockedGameName)
                                        setupValidationMessage = ""
                                        wizardStep = WizardStep.DONE
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("Finish Setup")
                                }
                            }
                            WizardStep.DONE -> {
                        // Display app version and game name at top
                        val appVersion = try {
                            packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0"
                        } catch (e: Exception) {
                            "0.1.0"
                        }
                        val gameLabel = if (BuildConfig.LOCK_GAME_SELECTION) {
                            val lockedName = gameDisplayName(BuildConfig.LOCKED_GAME_ID)
                            "$lockedName"
                        } else {
                            "Hypseus Android"
                        }
                        Text(
                            text = "$gameLabel • v$appVersion",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(8.dp)
                        )

                        // Tab navigation
                        TabRow(selectedTabIndex = selectedTab) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                text = { Text("Launcher") }
                            )
                            Tab(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                text = { Text("Logs") }
                            )
                        }

                        if (selectedTab == 0) {

                        val gameNames = configuredGameNames
                        val gameRequirements = mapOf(
                            "dle" to "Dragon's Lair Extended.\nSelect the DLe folder; framefile and DLe.singe are detected automatically.",
                            "dlclassic" to "Dragon's Lair Classic.\nSelect the dragons_lair_classic folder; framefile and dragons_lair_classic.singe are detected automatically.",
                            "dl2e" to "Dragon's Lair II Extended.\nSelect the DL2e folder; framefile and nested DL2e.singe are detected automatically.",
                            "sae" to "Space Ace Enhanced.\nSelect the SAe folder; framefile and nested SAe.singe are detected automatically.",
                            "singe" to "Singe/Lua game (Dragon's Lair Enhanced, Space Ace HD, etc.)\nNeeds: framefile (.txt), ROM dir, Singe script (.singe).",
                            "lair" to "Dragon's Lair (1983) — native game.\nNeeds: lair.txt framefile.\nROMs: place dl_f2_u1.bin through dl_f2_u4.bin inside romdir/lair/ (or romdir/lair.zip).\nNo Singe script.",
                            "laireuro" to "Dragon's Lair European version.\nNeeds: lair.txt framefile.\nROMs: place laireuro ROM .bin files inside romdir/laireuro/ (or laireuro.zip).\nNo Singe script.",
                            "lair2" to "Dragon's Lair II: Time Warp.\nNeeds: lair2.txt framefile.\nROMs: place lair2 ROM .bin files inside romdir/lair2/ (or lair2.zip).\nNo Singe script.",
                            "ace" to "Space Ace (1983) — native game.\nNeeds: ace.txt framefile.\nROMs: place sa_a3_u1.bin through sa_a3_u5.bin inside romdir/ace/ (or ace.zip).\nNo Singe script.",
                            "tq" to "Thayer's Quest.\nNeeds: tq.txt framefile and ROM set tq/ or tq.zip.\nNo Singe script.",
                            "astron" to "Astron Belt.\nNeeds: astron.txt framefile, ROM dir.\nNo Singe script.",
                            "badlands" to "Bad Lands.\nNeeds: badlands.txt framefile, ROM dir.\nNo Singe script.",
                            "bega" to "Bega's Battle.\nNeeds: bega.txt framefile, ROM dir.\nNo Singe script.",
                            "cliff" to "Cliff Hanger.\nNeeds: cliff.txt framefile, ROM dir.\nNo Singe script.",
                            "cobraconv" to "Cobra Command.\nNeeds: cobraconv.txt framefile, ROM dir.\nNo Singe script.",
                            "esh" to "Esh's Aurunmilla.\nNeeds: esh.txt framefile, ROM dir.\nNo Singe script.",
                            "ffr" to "Freedom Fighter.\nNeeds: ffr.txt framefile, ROM dir.\nNo Singe script.",
                            "firefox" to "Firefox (Atari).\nNeeds: firefox.txt framefile, ROM dir.\nNo Singe script.",
                            "gpworld" to "GP World.\nNeeds: gpworld.txt framefile, ROM dir.\nNo Singe script.",
                            "interstellar" to "Interstellar.\nNeeds: interstellar.txt framefile, ROM dir.\nNo Singe script.",
                            "lgp" to "Laser Grand Prix.\nNeeds: lgp.txt framefile, ROM dir.\nNo Singe script.",
                            "mach3" to "M.A.C.H. 3.\nNeeds: mach3.txt framefile, ROM dir.\nNo Singe script.",
                            "starrider" to "Star Rider.\nNeeds: starrider.txt framefile, ROM dir.\nNo Singe script.",
                            "sdq" to "Super Don Quix-ote.\nNeeds: sdq.txt framefile and ROM set sdq/ or sdq.zip.\nNo Singe script.",
                            "timetrav" to "Time Traveler.\nNeeds: timetrav.txt framefile, ROM dir.\nNo Singe script."
                        )
                        val selectedGame = gameName.trim().lowercase()
                        val isSinge = isSingeGame(selectedGame)
                        val needsRomDir = !isSinge
                        val showScoreboardOption = selectedGame == "lair" || selectedGame == "ace" || selectedGame == "tq"
                        if (lockedGameName == null) {
                            ExposedDropdownMenuBox(
                                expanded = gameNameExpanded,
                                onExpandedChange = { gameNameExpanded = it },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    value = gameDisplayName(gameName),
                                    onValueChange = {},
                                    label = { Text("Game Name") },
                                    singleLine = true,
                                    readOnly = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = gameNameExpanded) },
                                    modifier = Modifier
                                        .menuAnchor()
                                        .fillMaxWidth()
                                )
                                ExposedDropdownMenu(
                                    expanded = gameNameExpanded,
                                    onDismissRequest = { gameNameExpanded = false }
                                ) {
                                    gameNames.forEach { name ->
                                        DropdownMenuItem(
                                            text = { Text(gameDisplayName(name)) },
                                            onClick = {
                                                val previousGame = gameName.trim().lowercase()
                                                saveGameSettings(
                                                    prefs = prefs,
                                                    game = previousGame,
                                                    baseFolderPath = lairFolderPath,
                                                    framefilePath = framefilePath,
                                                    romDirPath = romDirPath,
                                                    singeScriptPath = singeScriptPath,
                                                    digitalScoreboard = digitalScoreboard,
                                                    assistMode = assistMode,
                                                    dipBankA = dipBankA,
                                                    dipBankB = dipBankB,
                                                )
                                                gameName = name
                                                gameNameExpanded = false
                                                setupValidationMessage = ""
                                                prefs.edit().putString("selected_game_name", name).apply()
                                                if (!isGameSetupSaved(prefs, name)) {
                                                    showGameSettings = false
                                                    wizardStep = WizardStep.SETUP_GAME
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        if (lockedGameName == null) {
                            Text(
                                text = gameRequirements[gameName.trim().lowercase()]
                                    ?: "Custom game name. Provide framefile and ROM dir.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Text(
                                text = if (isSinge) {
                                    "Launch args for this game use: -framefile, -singedir, and -script/-zlua."
                                } else {
                                    "Launch args for this game use: -framefile and -romdir."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        if (showGameSettings) {
                            OutlinedTextField(
                                value = lairFolderPath,
                                onValueChange = { lairFolderPath = it },
                                label = { Text("Base Folder") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = {
                                    pickerTarget = PickerTarget.BASE_FOLDER
                                    folderLauncher.launch(null)
                                }) {
                                    Text("Select Base Folder")
                                }
                            }

                            if (!singleFolderMode) {
                                OutlinedTextField(
                                    value = framefilePath,
                                    onValueChange = { framefilePath = it },
                                    label = { Text("Framefile Path (-framefile)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Button(onClick = {
                                        pickerTarget = PickerTarget.FRAMEFILE_FILE
                                        fileLauncher.launch(arrayOf("text/plain", "*/*"))
                                    }) {
                                        Text("Select Framefile")
                                    }
                                }

                                if (needsRomDir) {
                                    OutlinedTextField(
                                        value = romDirPath,
                                        onValueChange = { romDirPath = it },
                                        label = { Text("ROM Folder (-romdir)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Button(onClick = {
                                            pickerTarget = PickerTarget.ROM_FOLDER
                                            folderLauncher.launch(null)
                                        }) {
                                            Text("Select ROM Folder")
                                        }
                                    }
                                }

                                if (isSinge) {
                                    OutlinedTextField(
                                        value = singeScriptPath,
                                        onValueChange = { singeScriptPath = it },
                                        label = { Text("Singe Script/Zip (-script or -zlua)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Button(onClick = {
                                            pickerTarget = PickerTarget.SINGE_SCRIPT_FILE
                                            fileLauncher.launch(arrayOf("application/zip", "text/plain", "*/*"))
                                        }) {
                                            Text("Select Singe Script")
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    text = "One-folder mode: framefile and ROMs are auto-detected from the base folder.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            if (showScoreboardOption) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text("Digital scoreboard overlay")
                                    Switch(
                                        checked = digitalScoreboard,
                                        onCheckedChange = { digitalScoreboard = it },
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("Assist mode")
                                Switch(
                                    checked = assistMode,
                                    onCheckedChange = { assistMode = it },
                                )
                            }

                            // Audio delay option for Singe games (including locked Space Ace)
                            val gameNameForSettings = gameName.trim().lowercase()
                            val isSingeGame = isSingeGame(gameNameForSettings) || gameNameForSettings == "ace" || gameNameForSettings == "singe"
                            if (isSingeGame) {
                                OutlinedTextField(
                                    value = audioDelayMs,
                                    onValueChange = { audioDelayMs = it.filter { c -> c.isDigit() } },
                                    label = { Text("Audio Delay (ms) - Singe 2 sync fix") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("0") }
                                )
                                Text(
                                    text = "Set to 330 for Dragon's Lair, adjust to fix audio sync issues",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            if (!isSinge) {
                                OutlinedTextField(
                                    value = dipBankA,
                                    onValueChange = { dipBankA = it.filter(::isBinaryDigit).take(8) },
                                    label = { Text("DIP Bank A") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = dipBankB,
                                    onValueChange = { dipBankB = it.filter(::isBinaryDigit).take(8) },
                                    label = { Text("DIP Bank B") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = {
                                    val game = gameName.trim().lowercase()
                                    saveGameSettings(
                                        prefs = prefs,
                                        game = game,
                                        baseFolderPath = lairFolderPath,
                                        framefilePath = framefilePath,
                                        romDirPath = romDirPath,
                                        singeScriptPath = singeScriptPath,
                                        digitalScoreboard = digitalScoreboard,
                                        assistMode = assistMode,
                                        dipBankA = dipBankA,
                                        dipBankB = dipBankB,
                                        audioDelayMs = audioDelayMs,
                                    )
                                    prefs.edit().putString("selected_game_name", gameName).apply()
                                    configuredGameNames = loadConfiguredGames(prefs, game, lockedGameName)
                                    Toast.makeText(activityContext, "Saved settings for $gameName", Toast.LENGTH_SHORT).show()
                                }) {
                                    Text("Save")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(onClick = { showGameSettings = false }) {
                                    Text("Back")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    enabled = !running.value,
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    onClick = {
                                        val game = gameName.trim().lowercase()
                                        saveGameSettings(
                                            prefs = prefs,
                                            game = game,
                                            baseFolderPath = lairFolderPath,
                                            framefilePath = framefilePath,
                                            romDirPath = romDirPath,
                                            singeScriptPath = singeScriptPath,
                                            digitalScoreboard = digitalScoreboard,
                                            assistMode = assistMode,
                                            dipBankA = dipBankA,
                                            dipBankB = dipBankB,
                                        )
                                        configuredGameNames = loadConfiguredGames(prefs, game, lockedGameName)
                                        showGameSettings = false
                                        launchGame()
                                    }
                                ) {
                                    Text("Launch")
                                }
                            }
                        } else {
                            Text(
                                text = "Current folder: $lairFolderPath",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "Framefile: $framefilePath",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (needsRomDir) {
                                Text(
                                    text = "ROM folder: $romDirPath",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (isSinge) {
                                Text(
                                    text = "Singe script: $singeScriptPath",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                Text(
                                    text = "Assist: ${if (assistMode) "on" else "off"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (dipBankA.isNotBlank() || dipBankB.isNotBlank()) {
                                    Text(
                                        text = "DIP banks: A=${dipBankA.ifBlank { "default" }}, B=${dipBankB.ifBlank { "default" }}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = { showGameSettings = true }) {
                                    Text("Game Settings")
                                }
                                if (lockedGameName == null) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(onClick = {
                                        showGameSettings = false
                                        gameName = knownGameNames().firstOrNull { !isGameSetupSaved(prefs, it) } ?: "dle"
                                        setupValidationMessage = ""
                                        wizardStep = WizardStep.SETUP_GAME
                                    }) {
                                        Text("Add Game")
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(onClick = {
                                    val game = gameName.trim().lowercase()
                                    saveGameSettings(
                                        prefs = prefs,
                                        game = game,
                                        baseFolderPath = lairFolderPath,
                                        framefilePath = framefilePath,
                                        romDirPath = romDirPath,
                                        singeScriptPath = singeScriptPath,
                                        digitalScoreboard = digitalScoreboard,
                                        assistMode = assistMode,
                                        dipBankA = dipBankA,
                                        dipBankB = dipBankB,
                                    )
                                    prefs.edit().putString("selected_game_name", gameName).apply()
                                    configuredGameNames = loadConfiguredGames(prefs, game, lockedGameName)
                                    Toast.makeText(activityContext, "Saved settings for $gameName", Toast.LENGTH_SHORT).show()
                                }) {
                                    Text("Save Current")
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    enabled = !running.value,
                                    onClick = { launchGame() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(if (running.value) "Launching..." else "Run Hypseus")
                                }
                                // Quick test button for Space Ace on device
                                Button(
                                    enabled = !running.value && BuildConfig.LOCKED_GAME_ID == "ace" && lairFolderPath.isNotEmpty(),
                                    onClick = {
                                        // Use the currently selected folder to launch Space Ace
                                        if (lairFolderPath.isNotEmpty()) {
                                            detectGameLayout(
                                                game = "ace",
                                                baseFolder = lairFolderPath,
                                                requestedFramefile = "",
                                                requestedRomDir = "",
                                                requestedSingeScript = "",
                                            ).also { layout ->
                                                framefilePath = layout.framefile
                                                romDirPath = layout.romDir
                                                singeScriptPath = layout.singeScript
                                            }
                                            launchGame()
                                        } else {
                                            Toast.makeText(
                                                activityContext,
                                                "No game folder selected. Pick a folder first.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiary,
                                        disabledContainerColor = MaterialTheme.colorScheme.outlineVariant
                                    ),
                                    modifier = Modifier.weight(0.8f)
                                ) {
                                    Text("Quick Test")
                                }
                                Button(
                                    onClick = {
                                        // Reset wizard and clear all picker URIs for this game
                                        prefs.edit().apply {
                                            putBoolean("wizard_done", false)
                                            prefs.all.keys.filter { it.contains("_uri") }.forEach { remove(it) }
                                        }.apply()
                                        wizardStep = WizardStep.NEED_MEDIA_PERM
                                        selectedTab = 0
                                        launchDebugReport = ""
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                ) {
                                    Text("Reset Wizard")
                                }
                            }
                        }
                        } else if (selectedTab == 1) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("Launch Debug Log")
                                Button(onClick = {
                                    launchDebugReport = ""
                                    prefs.edit().remove("launch_debug_report").apply()
                                }) {
                                    Text("Clear")
                                }
                            }

                            OutlinedTextField(
                                value = launchDebugReport,
                                onValueChange = {},
                                label = { Text("Resolved startup command and paths") },
                                readOnly = true,
                                minLines = 16,
                                maxLines = 24,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        } // WizardStep.DONE
                    } // when (wizardStep)
                    }
                }
            }
        }
    }

    private fun buildLaunchDebugReport(
        game: String,
        requestedBaseFolder: String,
        requestedFramefile: String,
        requestedRomDir: String,
        requestedSingeScript: String,
        resolvedBaseFolder: String,
        resolvedFramefile: String,
        resolvedRomDir: String,
        resolvedSingeScript: String,
        args: List<String>,
        storageAccessReport: String,
    ): String {
        val framefileVideo = resolveFramefileVideoPath(resolvedFramefile)
        val reportLines = listOf(
            "Game: $game",
            "Command: app_process ${args.joinToString(" ")}",
            "Requested base: $requestedBaseFolder",
            "Requested framefile: $requestedFramefile",
            "Requested rom dir: $requestedRomDir",
            "Requested singe script: $requestedSingeScript",
            "Resolved base: $resolvedBaseFolder",
            "Resolved framefile: $resolvedFramefile (exists=${File(resolvedFramefile).isFile})",
            "Resolved rom dir: $resolvedRomDir (exists=${resolvedRomDir.isNotBlank() && File(resolvedRomDir).isDirectory})",
            "Resolved singe script: $resolvedSingeScript (exists=${resolvedSingeScript.isNotBlank() && File(resolvedSingeScript).isFile})",
            "Framefile media: ${framefileVideo ?: "<unresolved>"}",
            storageAccessReport,
        )
        return reportLines.joinToString("\n")
    }

    private fun addDipBankArgs(args: MutableList<String>, bank: Int, value: String) {
        val normalized = value.trim()
        if (normalized.length == 8 && normalized.all(::isBinaryDigit)) {
            args.add("-bank")
            args.add(bank.toString())
            args.add(normalized)
        }
    }

    private fun isBinaryDigit(char: Char): Boolean = char == '0' || char == '1'

    private fun performPreLaunchCleanup(context: Context): CleanupResult {
        val logsDir = File(filesDir, "logs")
        val screenshotsDir = File(filesDir, "screenshots")
        var bytesFreed = 0L

        if (logsDir.isDirectory) {
            val logs = logsDir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
            logs.drop(2).forEach { file ->
                bytesFreed += file.length()
                file.delete()
            }
        }

        if (screenshotsDir.isDirectory) {
            val shots = screenshotsDir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
            shots.drop(20).forEach { file ->
                bytesFreed += file.length()
                file.delete()
            }
        }

        // Encourage GC before launching a heavyweight native session.
        Runtime.getRuntime().gc()

        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val freeInternalBytes = filesDir.usableSpace
        val lowStorageCritical = freeInternalBytes < 512L * 1024L * 1024L || memInfo.lowMemory

        return CleanupResult(
            bytesFreed = bytesFreed,
            freeInternalBytes = freeInternalBytes,
            lowStorageCritical = lowStorageCritical,
        )
    }

    private fun validateRequiredLaunchFiles(
        game: String,
        baseFolder: String,
        framefile: String,
        romDir: String,
        singeScript: String,
    ): ValidationResult {
        val gameLower = normalizeLaunchGameName(game)
        val detectedLayout = detectGameLayout(
            game = gameLower,
            baseFolder = baseFolder,
            requestedFramefile = framefile,
            requestedRomDir = romDir,
            requestedSingeScript = singeScript,
        )
        val resolvedFramefile = detectedLayout.framefile
        val resolvedRomDir = detectedLayout.romDir
        val resolvedSingeScript = detectedLayout.singeScript
        val missing = mutableListOf<String>()
        val structureDetails = buildString {
            append("Detected structure:\n")
            detectedLayout.reportLines.forEach { line ->
                append("- ")
                append(line)
                append("\n")
            }
        }.trimEnd()

        if (baseFolder.isBlank() || !File(baseFolder).isDirectory) {
            missing += "Base folder missing: $baseFolder"
        }

        if (resolvedFramefile.isBlank() || !File(resolvedFramefile).isFile) {
            missing += "Framefile missing: $resolvedFramefile"
        } else {
            // Skip m2v validation for Singe mode (uses ROMs, not video)
            if (!isSingeGame(gameLower) && gameLower != "singe") {
                val media = resolveFramefileVideoPath(resolvedFramefile)
                if (media.isNullOrBlank() || !File(media).exists()) {
                    missing += "Framefile media missing: ${media ?: "<unresolved>"}"
                }
            }
        }

        if (isSingeGame(gameLower) || gameLower == "singe") {
            if (resolvedSingeScript.isBlank() || !File(resolvedSingeScript).isFile) {
                missing += "Singe script missing: $resolvedSingeScript"
            }
            if (baseFolder.isNotBlank() && File(baseFolder).isDirectory) {
                val externalMissing = validateSingeFolder(File(baseFolder))
                if (externalMissing.isNotEmpty() && resolvedSingeScript.isNotBlank()) {
                    // External folder failed framework/asset check — try the script's parent
                    // directory as fallback (e.g. filesDir/singe/SAe when using bundled assets).
                    val scriptParent = File(resolvedSingeScript).parentFile
                    if (scriptParent != null && scriptParent.canonicalPath != File(baseFolder).canonicalPath) {
                        missing += validateSingeFolder(scriptParent)
                    } else {
                        missing += externalMissing
                    }
                } else {
                    missing += externalMissing
                }
            }
        } else {
            val requiredRoms = expectedRomFiles(gameLower)
            if (requiredRoms.isNotEmpty()) {
                if (resolvedRomDir.isBlank() || !File(resolvedRomDir).isDirectory) {
                    missing += "ROM dir missing: $resolvedRomDir"
                } else {
                    missing += validateRomSet(
                        romRoot = File(resolvedRomDir),
                        romSetNames = romSetCandidatesForGame(gameLower),
                        requiredFiles = requiredRoms,
                    )
                }
            }
        }

        return if (missing.isEmpty()) {
            ValidationResult(ok = true, message = "OK\n$structureDetails")
        } else {
            ValidationResult(ok = false, message = "$structureDetails\nMissing:\n${missing.joinToString("\n")}")
        }
    }

    private fun validateSingeFolder(baseFolder: File): List<String> {
        val missing = mutableListOf<String>()
        val required = mutableListOf<File>()

        val classicStructure = File(baseFolder, "Structure")
        val kimmyStructure = File(baseFolder, "singe/FrameworkKimmy")
        val parentFramework = File(baseFolder, "Framework")
        val parentKimmyFramework = File(baseFolder, "FrameworkKimmy")
        val nestedGameDir = File(baseFolder, baseFolder.name)
        var assetDir = baseFolder
        when {
            classicStructure.isDirectory -> {
                required += File(classicStructure, "globals.singe")
                required += File(classicStructure, "framework.singe")
                required += File(classicStructure, "main.singe")
                required += File(classicStructure, "hscore.singe")
                required += File(classicStructure, "service.singe")
                required += File(classicStructure, "toolbox.singe")
            }
            kimmyStructure.isDirectory -> {
                required += File(kimmyStructure, "globals.singe")
                required += File(kimmyStructure, "framework.singe")
                required += File(kimmyStructure, "main.singe")
                required += File(kimmyStructure, "hscore.singe")
                required += File(kimmyStructure, "service.singe")
                required += File(kimmyStructure, "toolbox.singe")
            }
            parentFramework.isDirectory -> {
                assetDir = nestedGameDir
                required += File(parentFramework, "globals.singe")
                required += File(parentFramework, "framework.singe")
                required += File(parentFramework, "main.singe")
                required += File(parentFramework, "hscore.singe")
                required += File(parentFramework, "service.singe")
                required += File(parentFramework, "toolbox.singe")
            }
            parentKimmyFramework.isDirectory -> {
                assetDir = nestedGameDir
                required += File(parentKimmyFramework, "globals.singe")
                required += File(parentKimmyFramework, "framework.singe")
                required += File(parentKimmyFramework, "main.singe")
                required += File(parentKimmyFramework, "hscore.singe")
                required += File(parentKimmyFramework, "service.singe")
                required += File(parentKimmyFramework, "toolbox.singe")
            }
            File(nestedGameDir, "Structure").isDirectory -> {
                assetDir = nestedGameDir
                val nestedStructure = File(nestedGameDir, "Structure")
                required += File(nestedStructure, "globals.singe")
                required += File(nestedStructure, "framework.singe")
                required += File(nestedStructure, "main.singe")
                required += File(nestedStructure, "hscore.singe")
                required += File(nestedStructure, "service.singe")
                required += File(nestedStructure, "toolbox.singe")
            }
            else -> missing += "Singe framework folder missing under: ${baseFolder.absolutePath}"
        }

        required += File(assetDir, "Script/addons.singe")
        required += File(assetDir, "Sounds")
        required += File(assetDir, "Overlay")

        required.forEach { file ->
            if (!file.exists()) missing += "Singe file missing: ${file.absolutePath}"
        }
        return missing
    }

    private fun normalizeLaunchGameName(game: String): String {
        return when (game.trim().lowercase()) {
            "singe", "dragon's lair extended", "dragons lair extended", "dle" -> "dle"
            "dragon's lair classic", "dragons lair classic", "dragons_lair_classic", "dlclassic" -> "dlclassic"
            "dragon's lair ii extended", "dragons lair ii extended", "dragon's lair 2 extended", "dragons lair 2 extended", "dl2e" -> "dl2e"
            "space ace enhanced", "space ace extended", "sae" -> "sae"
            "lair" -> "dlclassic"
            "thayers" -> "tq"
            "superd", "superdon" -> "sdq"
            else -> game.trim().lowercase()
        }
    }

    private fun isSingeGame(game: String): Boolean {
        return when (normalizeLaunchGameName(game)) {
            "dle", "dlclassic", "dl2e", "sae" -> true
            else -> false
        }
    }

    private fun gameDisplayName(game: String): String {
        return when (normalizeLaunchGameName(game)) {
            "dle" -> "Dragon's Lair Extended"
            "dlclassic" -> "Dragon's Lair Classic"
            "dl2e" -> "Dragon's Lair II Extended"
            "sae" -> "Space Ace Enhanced"
            else -> game
        }
    }

    private fun romSetNameForGame(game: String): String {
        return when (normalizeLaunchGameName(game)) {
            "tq" -> "tq"
            "sdq" -> "sdq"
            else -> normalizeLaunchGameName(game)
        }
    }

    private fun romSetCandidatesForGame(game: String): List<String> {
        return when (normalizeLaunchGameName(game)) {
            "ace" -> listOf("ace", "sae", "spaceace", "space_ace")
            else -> listOf(romSetNameForGame(game))
        }
    }

    private fun normalizeBaseFolderSelection(baseFolder: String, game: String): String {
        val base = File(baseFolder)
        if (!base.isDirectory) return baseFolder

        val folderName = base.name.trim().lowercase()
        val likelyMediaLeaf = folderName in setOf("video", "videos", "media", "sounds", "sound", "overlay", "script")

        val candidates: List<File> = buildList<File> {
            add(base)
            base.parentFile?.let { add(it) }
            base.parentFile?.parentFile?.let { add(it) }
        }.distinctBy { candidate -> runCatching { candidate.canonicalPath }.getOrElse { candidate.absolutePath } }

        val preferred = candidates.firstOrNull { candidate ->
            looksLikeGameRoot(candidate, game)
        }

        return when {
            preferred != null -> preferred.absolutePath
            likelyMediaLeaf && base.parentFile?.isDirectory == true -> base.parentFile?.absolutePath ?: baseFolder
            else -> baseFolder
        }
    }

    private fun looksLikeGameRoot(candidate: File, game: String): Boolean {
        if (!candidate.isDirectory) return false

        val normalizedGame = normalizeLaunchGameName(game)
        val frameGuess = detectFramefileFromBase(normalizedGame, candidate.absolutePath, "")
        if (frameGuess.isNotBlank() && File(frameGuess).isFile) return true

        if (isSingeGame(normalizedGame) || normalizedGame == "singe" || normalizedGame == "ace") {
            val scriptGuess = guessDefaultSingeScript(candidate.absolutePath)
            if (scriptGuess.isNotBlank() && File(scriptGuess).isFile) return true
        }

        val romGuess = detectRomDirFromBase(normalizedGame, candidate.absolutePath)
        if (!romGuess.isNullOrBlank() && File(romGuess).isDirectory) return true

        return false
    }

    private fun detectGameLayout(
        game: String,
        baseFolder: String,
        requestedFramefile: String,
        requestedRomDir: String,
        requestedSingeScript: String,
    ): DetectedLayout {
        val normalizedGame = normalizeLaunchGameName(game)
        var framefilePath = requestedFramefile
        var romDirPath = requestedRomDir.ifBlank { baseFolder }
        var singeScriptPath = requestedSingeScript
        val report = mutableListOf<String>()

        val lockedGameId = BuildConfig.LOCKED_GAME_ID
        val normalizedLockedGame = normalizeLaunchGameName(lockedGameId)

        val base = File(baseFolder)
        report += "Base folder: $baseFolder (exists=${base.isDirectory})"

        // Framefile: use user-provided storage only (no bundled fallback)
        if (framefilePath.isBlank() || !File(framefilePath).isFile) {
            framefilePath = guessDefaultFramefile(baseFolder, normalizedGame)
        }

        if (isSingeGame(normalizedGame) || normalizedGame == "singe") {
            romDirPath = baseFolder
            val singeScriptFile = File(singeScriptPath)
            // A script in internal app storage (filesDir) is intentional — don't replace it.
            val singeOutsideBase = singeScriptPath.isNotBlank() &&
                !isPathUnder(base, singeScriptFile) &&
                !isPathUnder(filesDir, singeScriptFile)
            if (singeScriptPath.isBlank() || !singeScriptFile.isFile || singeOutsideBase) {
                singeScriptPath = guessDefaultSingeScript(baseFolder)
            }
        } else {
            val framefileFile = File(framefilePath)
            // Framefile must be in user-provided external storage
            val framefileOutsideBase = framefilePath.isNotBlank() && 
                !isPathUnder(base, framefileFile)
            if (framefilePath.isBlank() || !framefileFile.isFile || framefileOutsideBase) {
                framefilePath = guessDefaultFramefile(baseFolder, normalizedGame)
            }

            val romDirFile = File(romDirPath)
            val romOutsideBase = romDirPath.isNotBlank() && !isPathUnder(base, romDirFile)
            if (romDirPath.isBlank() || !romDirFile.isDirectory || romOutsideBase) {
                romDirPath = baseFolder
            }
            val detectedRomDir = detectRomDirFromBase(normalizedGame, baseFolder)
            if (detectedRomDir != null) {
                romDirPath = detectedRomDir
            }
            romDirPath = resolveRomDirForGame(normalizedGame, romDirPath)
        }

        report += "Framefile: $framefilePath (exists=${File(framefilePath).isFile})"
        if (!(isSingeGame(normalizedGame) || normalizedGame == "singe")) {
            report += "ROM root: $romDirPath (exists=${File(romDirPath).isDirectory})"
        }
        if (isSingeGame(normalizedGame) || normalizedGame == "singe") {
            report += "Singe script: $singeScriptPath (exists=${File(singeScriptPath).isFile})"
        }

        return DetectedLayout(
            framefile = framefilePath,
            romDir = romDirPath,
            singeScript = singeScriptPath,
            reportLines = report,
        )
    }

    private fun detectFramefileFromBase(game: String, baseFolder: String, fallback: String): String {
        val base = File(baseFolder)
        if (!base.isDirectory) return fallback

        val romSet = romSetNameForGame(game)
        val candidates = mutableListOf<File>()
        candidates += File(base, "$romSet.txt")
        candidates += File(base, "${base.name}.txt")
        candidates += File(base, "framefile.txt")
        candidates += File(base, "$romSet/$romSet.txt")
        candidates += File(base, "vldp/$romSet/$romSet.txt")
        if (normalizeLaunchGameName(game) == "ace") {
            candidates += File(base, "ace.txt")
            candidates += File(base, "SAe.txt")
            candidates += File(base, "sae.txt")
            candidates += File(base, "SAe/SAe.txt")
            candidates += File(base, "SAe/sae.txt")
            candidates += File(base, "spaceace/ace.txt")
            candidates += File(base, "spaceace/sae.txt")
        }

        candidates.firstOrNull { it.isFile }?.let { return it.absolutePath }

        val recursiveMatch = if (normalizeLaunchGameName(game) == "ace") {
            findFirstNamedFile(base, "SAe.txt", 4)
                ?: findFirstNamedFile(base, "sae.txt", 4)
                ?: findFirstNamedFile(base, "ace.txt", 4)
                ?: findFirstNamedFile(base, "framefile.txt", 4)
        } else {
            findFirstNamedFile(base, "$romSet.txt", 4)
                ?: findFirstNamedFile(base, "framefile.txt", 4)
        }
        return recursiveMatch?.absolutePath ?: fallback
    }

    private fun isPathUnder(baseDir: File, path: File): Boolean {
        return runCatching {
            val baseCanonical = baseDir.canonicalFile
            val pathCanonical = path.canonicalFile
            pathCanonical.path.startsWith(baseCanonical.path + File.separator) || pathCanonical == baseCanonical
        }.getOrDefault(false)
    }

    private fun detectRomDirFromBase(game: String, baseFolder: String): String? {
        val base = File(baseFolder)
        if (!base.isDirectory) return null

        val romSetNames = romSetCandidatesForGame(game)
        val rootCandidates = listOf(
            base,
            File(base, "roms"),
            File(base, "rom"),
            File(base, "arcade"),
            File(base, "arcade/roms"),
            File(base, normalizeLaunchGameName(game)),
            File(base, "spaceace"),
        )

        rootCandidates.firstOrNull { candidate ->
            candidate.isDirectory && hasAnyRomSet(candidate, romSetNames)
        }?.let { return it.absolutePath }

        // Also check standard Hypseus location for ROM zips
        val standardPath = extractStorageRoot(baseFolder) + "/hypseus_singe_data/00-zip-roms/00-Framework-zip-rom"
        val standardDir = File(standardPath)
        if (standardDir.isDirectory && hasAnyRomSet(standardDir, romSetNames)) {
            Log.d("HypseusMain", "Found ROM in standard Hypseus location: $standardPath")
            return standardPath
        }

        return base.listFiles()
            ?.filter { it.isDirectory }
            ?.firstOrNull { hasAnyRomSet(it, romSetNames) }
            ?.absolutePath
    }

    private fun hasAnyRomSet(root: File, romSetNames: List<String>): Boolean {
        return romSetNames.any { romSet ->
            findChildIgnoreCase(root, "$romSet.zip")?.isFile == true ||
                findChildIgnoreCase(root, romSet)?.isDirectory == true
        }
    }

    private fun findFirstNamedFile(root: File, fileName: String, maxDepth: Int): File? {
        return root.walkTopDown()
            .maxDepth(maxDepth)
            .firstOrNull { it.isFile && it.name.equals(fileName, ignoreCase = true) }
    }

    private fun resolveRomDirForGame(game: String, romDir: String): String {
        val normalizedGame = normalizeLaunchGameName(game)
        if (romDir.isBlank()) return romDir

        val romRoot = File(romDir)
        if (!romRoot.isDirectory) return romDir

        if (normalizedGame == "ace") {
            val nestedSae = findChildIgnoreCase(romRoot, "SAe")
            if (nestedSae?.isDirectory == true) {
                val hasSaeZip = findChildIgnoreCase(nestedSae, "sae.zip")?.isFile == true
                val hasAceZip = findChildIgnoreCase(nestedSae, "ace.zip")?.isFile == true
                val hasSaeDir = findChildIgnoreCase(nestedSae, "sae")?.isDirectory == true
                val hasAceDir = findChildIgnoreCase(nestedSae, "ace")?.isDirectory == true
                if (hasSaeZip || hasAceZip || hasSaeDir || hasAceDir) {
                    return nestedSae.absolutePath
                }
            }

            val nestedSpaceAce = findChildIgnoreCase(romRoot, "spaceace")
            if (nestedSpaceAce?.isDirectory == true) {
                val hasAceZip = findChildIgnoreCase(nestedSpaceAce, "ace.zip")?.isFile == true
                val hasAceDir = findChildIgnoreCase(nestedSpaceAce, "ace")?.isDirectory == true
                if (hasAceZip || hasAceDir) {
                    return nestedSpaceAce.absolutePath
                }
            }
        }

        return romDir
    }

    private fun expectedRomFiles(game: String): List<String> {
        return when (normalizeLaunchGameName(game)) {
            "lair" -> listOf("dl_f2_u1.bin", "dl_f2_u2.bin", "dl_f2_u3.bin", "dl_f2_u4.bin")
            "laireuro" -> listOf("elu45.bin", "elu46.bin", "elu47.bin", "elu48.bin", "elu33.bin")
            "lair2" -> listOf("dl2_319.bin")
            "ace" -> listOf("sa_a3_u1.bin", "sa_a3_u2.bin", "sa_a3_u3.bin", "sa_a3_u4.bin", "sa_a3_u5.bin")
            "tq" -> listOf("tq_u33.bin", "tq_u1.bin", "tq_cop.bin")
            "astron" -> listOf("5473c", "5474a", "5284", "5285", "5280", "5281", "5286", "5338", "5279", "pr-5278.bin", "pr-5277.bin", "pr-5276.bin", "pr-5275.bin")
            "badlands" -> listOf("badlands.a13", "badlands.a14", "badlands.c8", "badlands.c4")
            "bega" -> listOf("an05-3", "an04-3", "an03-3", "an02-3", "an01", "an00-3", "an06", "an0c", "an0b", "an0a", "an09", "an08", "an07")
            "cliff" -> listOf("cliff_u1.bin", "cliff_u2.bin", "cliff_u3.bin", "cliff_u4.bin", "cliff_u5.bin")
            "cobraconv" -> listOf("bd00", "bd01", "bd02", "bd03", "bd07", "bd06", "bd05", "bd04", "vd0-c.bpr", "vd0-t.bpr", "lp4-2.pld")
            "esh" -> listOf("h8_is1.bin", "f8_is2.bin", "m3_a.bin", "l3_b.bin", "k3_c.bin", "j1_rgb.bin", "c5_h.bin", "c6_v.bin")
            "firefox" -> listOf("136026.109", "136026.110", "136026.111", "136026.101", "136026.102", "136026.105", "136026.106", "136026.125")
            "gpworld" -> listOf("gpw_6162a.bin", "gpw_6163.bin", "gpw_6164.bin", "gpw_6148.bin", "gpw_6149.bin", "gpw_6150.bin", "gpw_6151.bin", "gpw_6152.bin", "gpw_6153.bin", "gpw_6154.bin", "gpw_6155.bin", "gpw_6156.bin", "gpw_6157.bin", "gpw_6158.bin", "gpw_6146.bin", "gpw_6147.bin", "gpw_5501.bin")
            "interstellar" -> listOf("rom2.top", "rom3.top", "rom4.top", "rom5.top", "rom6.top", "rom1.top", "rom11.bot", "rom7.bot", "rom8.bot", "rom9.bot", "red6b.bot", "green6c.bot", "blue6d.bot")
            "lgp" -> listOf("a02_01.bin", "a02_02.bin", "a02_03.bin", "a02_04.bin", "A02_29.bin", "a02_14.bin", "a02_15.bin", "a02_16.bin", "a02_17.bin")
            "mach3" -> listOf("m3rom4.bin", "m3rom3.bin", "m3rom2.bin", "m3rom1.bin", "m3rom0.bin", "m3drom1.bin", "m3yrom1.bin", "mach3fg3.bin", "mach3fg2.bin", "mach3fg1.bin", "mach3fg0.bin", "m3target.bin", "mach3bg0.bin", "mach3bg1.bin")
            "sdq" -> listOf("sdq-prog.bin", "sdq-char.bin", "sdq-cprm.bin")
            "timetrav" -> listOf("TT061891.BIN")
            else -> emptyList()
        }
    }

    private fun validateRomSet(romRoot: File, romSetNames: List<String>, requiredFiles: List<String>): List<String> {
        for (romSetName in romSetNames) {
            val romZip = findChildIgnoreCase(romRoot, "$romSetName.zip")
            if (romZip?.isFile == true) {
                val entries = zipBasenames(romZip)
                if (entries == null) return listOf("ROM zip unreadable: ${romZip.absolutePath}")
                val missing = requiredFiles.filter { it.lowercase() !in entries }
                return if (missing.isEmpty()) {
                    emptyList()
                } else {
                    listOf("ROM zip ${romZip.absolutePath} is missing: ${missing.joinToString(", ")}")
                }
            }

            val romSubdir = findChildIgnoreCase(romRoot, romSetName)
            if (romSubdir?.isDirectory == true) {
                val missing = requiredFiles.filter { findChildIgnoreCase(romSubdir, it)?.isFile != true }
                return if (missing.isEmpty()) {
                    emptyList()
                } else {
                    listOf("ROM folder ${romSubdir.absolutePath} is missing: ${missing.joinToString(", ")}")
                }
            }
        }

        val expectedTargets = romSetNames.joinToString(" or ") { romSetName ->
            "${File(romRoot, "$romSetName.zip").absolutePath} / ${File(romRoot, romSetName).absolutePath}"
        }
        return listOf("ROM set missing: expected one of $expectedTargets")
    }

    private fun zipBasenames(zip: File): Set<String>? {
        return runCatching {
            ZipFile(zip).use { archive ->
                archive.entries().asSequence()
                    .filter { !it.isDirectory }
                    .map { it.name.substringAfterLast('/').lowercase() }
                    .toSet()
            }
        }.getOrNull()
    }

    private fun prepareAceRomDirAlias(romDir: String, baseFolder: String): String {
        val romRoot = File(romDir)
        if (!romRoot.isDirectory) return romDir

        // Native ace loader expects ace.zip or ace/ under romdir.
        val hasAceZip = findChildIgnoreCase(romRoot, "ace.zip")?.isFile == true
        val hasAceDir = findChildIgnoreCase(romRoot, "ace")?.isDirectory == true
        if (hasAceZip || hasAceDir) return romDir

        val saeZipInRomDir = findChildIgnoreCase(romRoot, "sae.zip")
        val spaceAceZipInRomDir = findChildIgnoreCase(romRoot, "spaceace.zip")
        val saeDirInRomDir = findChildIgnoreCase(romRoot, "SAe")
            ?: findChildIgnoreCase(romRoot, "sae")
            ?: findChildIgnoreCase(romRoot, "spaceace")

        val aliasRoot = File(filesDir, "rom_alias/ace").apply { mkdirs() }

        // Prefer zip alias if available.
        val sourceZip = when {
            saeZipInRomDir?.isFile == true -> saeZipInRomDir
            spaceAceZipInRomDir?.isFile == true -> spaceAceZipInRomDir
            else -> null
        }
        if (sourceZip != null) {
            val aliasZip = File(aliasRoot, "ace.zip")
            runCatching {
                sourceZip.inputStream().use { input ->
                    aliasZip.outputStream().use { output -> input.copyTo(output) }
                }
                Log.d("HypseusMain", "Created ace.zip alias from ${sourceZip.absolutePath} -> ${aliasZip.absolutePath}")
                return aliasRoot.absolutePath
            }.onFailure {
                Log.w("HypseusMain", "Failed to create ace.zip alias: ${it.message}")
            }
        }

        // If ROMs are unpacked in SAe/ then alias required ace files into internal ace/.
        if (saeDirInRomDir?.isDirectory == true) {
            val aliasAceDir = File(aliasRoot, "ace").apply { mkdirs() }
            val required = expectedRomFiles("ace")
            var copiedAny = false
            required.forEach { fileName ->
                val src = findChildIgnoreCase(saeDirInRomDir, fileName)
                if (src?.isFile == true) {
                    val dst = File(aliasAceDir, fileName)
                    runCatching {
                        src.inputStream().use { input ->
                            dst.outputStream().use { output -> input.copyTo(output) }
                        }
                        copiedAny = true
                    }
                }
            }
            if (copiedAny) {
                Log.d("HypseusMain", "Created ace/ ROM alias folder from ${saeDirInRomDir.absolutePath}")
                return aliasRoot.absolutePath
            }
        }

        // Try one level up under picked base folder as a final fallback.
        val base = File(baseFolder)
        val baseRoms = findChildIgnoreCase(base, "Roms") ?: findChildIgnoreCase(base, "roms")
        if (baseRoms?.isDirectory == true && baseRoms.absolutePath != romRoot.absolutePath) {
            return prepareAceRomDirAlias(baseRoms.absolutePath, baseFolder)
        }

        return romDir
    }

    private fun findChildIgnoreCase(parent: File, childName: String): File? {
        val exact = File(parent, childName)
        if (exact.exists()) return exact
        return parent.listFiles()?.firstOrNull { it.name.equals(childName, ignoreCase = true) }
    }

    private fun resolveFramefileVideoPath(framefilePath: String): String? {
        val framefile = File(framefilePath)
        if (!framefile.isFile) return null

        val lines = framefile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        if (lines.size < 2) return null

        val videoRoot = lines[0]
        val videoFile = lines[1].split(Regex("\\s+"), limit = 2).getOrNull(1) ?: return null

        val rootDir = when {
            videoRoot == "." || videoRoot == "./" -> framefile.parentFile
            videoRoot.startsWith("/") -> File(videoRoot)
            else -> File(framefile.parentFile, videoRoot)
        }

        return File(rootDir, videoFile).absolutePath
    }

    private fun prepareLairFramefile(originalFramefile: String, baseFolder: String): String {
        return prepareGameFolderFramefile(
            originalFramefile = originalFramefile,
            baseFolder = baseFolder,
            suffix = "lair",
        )
    }

    private fun prepareGameFolderFramefile(originalFramefile: String, baseFolder: String, suffix: String): String {
        val original = File(originalFramefile)
        if (!original.isFile) return originalFramefile

        val originalLines = runCatching { original.readLines() }.getOrElse { return originalFramefile }
        if (originalLines.isEmpty()) return originalFramefile

        val rootIndex = originalLines.indexOfFirst { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && !trimmed.startsWith("#")
        }
        if (rootIndex < 0) return originalFramefile

        val baseVideoDir = File(baseFolder, "Video")
        val siblingVideoDir = File(original.parentFile, "Video")
        val videoDir = when {
            baseVideoDir.isDirectory -> baseVideoDir
            siblingVideoDir.isDirectory -> siblingVideoDir
            else -> return originalFramefile
        }

        val normalizedLines = originalLines.toMutableList()
        normalizedLines[rootIndex] = videoDir.absolutePath

        val mediaIndex = (rootIndex + 1 until normalizedLines.size).firstOrNull { idx ->
            val trimmed = normalizedLines[idx].trim()
            trimmed.isNotEmpty() && !trimmed.startsWith("#")
        }

        if (mediaIndex != null) {
            val tokens = normalizedLines[mediaIndex].trim().split(Regex("\\s+"), limit = 2)
            if (tokens.size == 2) {
                var mediaFile = tokens[1].replace("\\", "/")
                mediaFile = mediaFile.removePrefix("./")
                if (mediaFile.startsWith("Video/", ignoreCase = true)) {
                    mediaFile = mediaFile.substring("Video/".length)
                }
                mediaFile = resolveFramefileMediaName(videoDir, mediaFile)
                normalizedLines[mediaIndex] = "${tokens[0]} $mediaFile"
            }
        }

        val targetDir = File(filesDir, "framefiles")
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        val target = File(targetDir, "${original.nameWithoutExtension}_android_$suffix.txt")
        return runCatching {
            target.writeText(normalizedLines.joinToString("\n") + "\n")
            target.absolutePath
        }.getOrElse {
            Log.w("HypseusMain", "prepareGameFolderFramefile: failed to write override: ${it.message}")
            originalFramefile
        }
    }

    private fun resolveFramefileMediaName(videoDir: File, mediaFile: String): String {
        val requestedName = mediaFile.replace("\\", "/").substringAfterLast('/').trim()
        if (requestedName.isBlank()) return mediaFile

        val direct = File(videoDir, requestedName)
        if (direct.isFile) return requestedName

        val caseInsensitive = videoDir.listFiles()
            ?.firstOrNull { it.isFile && it.name.equals(requestedName, ignoreCase = true) }
        if (caseInsensitive != null) return caseInsensitive.name

        val ext = requestedName.substringAfterLast('.', "")
        if (ext.isNotBlank()) {
            val sameExtCandidates = videoDir.listFiles()
                ?.filter { it.isFile && it.extension.equals(ext, ignoreCase = true) }
                .orEmpty()
            if (sameExtCandidates.size == 1) {
                return sameExtCandidates.first().name
            }
        }

        return requestedName
    }

    /**
     * Sync framework files from external storage to internal runtime locations.
     *
     * Different packs use either Framework/ or FrameworkKimmy/, and the Singe runtime can
     * resolve "./Framework/..." from multiple working directories depending on launch mode.
     * Keep all runtime lookup locations hydrated.
     */
    private fun syncFrameworkFilesFromExternalStorage(baseFolder: String) {
        val base = File(baseFolder)
        val externalFramework = listOf(
            File(base, "Framework"),
            File(base, "FrameworkKimmy"),
            File(base.parentFile, "Framework"),
            File(base.parentFile, "FrameworkKimmy"),
        ).firstOrNull { it.isDirectory } ?: return

        val internalFrameworkDirs = mutableListOf(
            File(filesDir, "Framework"),
            File(filesDir, "singe/Framework"),
        )

        File(filesDir, "patched_singe").listFiles()?.forEach { gameDir ->
            if (gameDir.isDirectory) {
                internalFrameworkDirs += File(gameDir, "Framework")
            }
        }

        internalFrameworkDirs
            .distinctBy { it.absolutePath }
            .forEach { internalFramework ->
                internalFramework.mkdirs()

                externalFramework.walkTopDown().forEach { source ->
                    val relPath = source.relativeTo(externalFramework).path
                    val dest = File(internalFramework, relPath)
                    when {
                        source.isDirectory -> dest.mkdirs()
                        source.isFile && (source.extension.equals("singe", ignoreCase = true) || source.name.endsWith(".ttf", ignoreCase = true)) -> {
                            try {
                                source.copyTo(dest, overwrite = true)
                                Log.d("HypseusMain", "Synced framework file: ${dest.absolutePath}")
                            } catch (e: Exception) {
                                Log.w("HypseusMain", "Failed to sync framework file ${source.name}: ${e.message}")
                            }
                        }
                    }
                }

                val globalsFile = File(internalFramework, "globals.singe")
                if (globalsFile.isFile) {
                    normalizeSingeFrameworkGlobals(globalsFile)
                }
            }
    }

    private fun prepareSingeScriptForAndroid(originalScript: String): SingeLaunchScript? {
        val source = File(originalScript)
        if (!source.isFile) return null

        val gameDir = source.parentFile ?: return null
        val parentDir = gameDir.parentFile
            // Only treat an adjacent Framework/ as the "parent framework" when the script is on
            // external storage.  For scripts inside filesDir (bundled assets), we always use the
            // BASEDIR="singe" convention so the engine resolves paths via filesDir/singe/Framework/.
            val isInternalScript = isPathUnder(filesDir, source)
            val hasParentFramework = !isInternalScript && parentDir != null &&
                (File(parentDir, "Framework").isDirectory || File(parentDir, "FrameworkKimmy").isDirectory)
        val singeDir = if (hasParentFramework) parentDir ?: gameDir else gameDir
        val patchedDir = File(filesDir, "patched_singe/${gameDir.name}").apply { mkdirs() }
        val patched = File(patchedDir, source.name)

        // Determine the BASEDIR and MYDIR values to patch into the script.
        // When the game folder sits beside a Framework/ directory (the normal desktop layout),
        // the parent folder is the CWD (singeDir) and BASEDIR can be ".".
        // When there is no Framework beside the game, the script likely uses BASEDIR="singe"
        // so that Framework files live at <homedir>/singe/Framework/.  Preserve that so the
        // engine can find the framework relative to homedir/CWD.  In that case, also do NOT
        // patch MYDIR — let the original script expression (BASEDIR.."/"..gameName) evaluate
        // to "singe/<gameName>" which lua_espath will expand via -singedir.
        val baseDir: String?
        val myDir: String?
        if (hasParentFramework) {
            baseDir = "."
            myDir = gameDir.name
        } else if (File(gameDir, "singe/FrameworkKimmy").isDirectory) {
            baseDir = "singe"
            myDir = "."
        } else {
            // Script uses BASEDIR="singe".  We patch MYDIR to use triple-slash notation
            // (e.g. "singe///SAe") so that lua_espath resolves paths correctly.
            // After stripping the "singe/" prefix, "//SAe/..." makes bSet reach 2
            // before the first content char, preventing the ".hypseus" insertion.
            baseDir = "singe"
            myDir = "singe///${gameDir.name}"
        }

        // If we need singe/Framework but it does not yet exist under filesDir, try to
        // copy it from a sibling game folder on the SD card.
        if (baseDir == "singe" && !hasParentFramework) {
            ensureSingeFramework(sdCardSearchRoot = parentDir)
        }

        return runCatching {
            val patchedText = patchSingePathConstants(
                script = source.readText(),
                baseDir = baseDir,
                gameDir = myDir,
            )
            patched.writeText(patchedText)
            SingeLaunchScript(scriptPath = patched.absolutePath, singeDir = singeDir.absolutePath)
        }.getOrElse {
            Log.w("HypseusMain", "prepareSingeScriptForAndroid: failed to patch ${source.absolutePath}: ${it.message}")
            null
        }
    }



    /**
     * Ensures that [filesDir]/singe/Framework/ contains the Singe framework .singe files.
     *
     * When the user's game folder does not ship its own Framework/ directory (e.g. SAe), we
     * look for a Framework/ directory in sibling game folders under [sdCardSearchRoot] and copy
     * the files into internal storage so Hypseus can open them via the relative path
     * "singe/Framework/…" (CWD = homedir = filesDir).
     */
    private fun ensureSingeFramework(sdCardSearchRoot: File?) {
        val destFramework = File(filesDir, "singe/Framework")
        // If already populated, nothing to do.
        if (destFramework.isDirectory && (destFramework.listFiles()?.any { it.extension == "singe" } == true)) {
            normalizeSingeFrameworkGlobals(File(destFramework, "globals.singe"))
            Log.d("HypseusMain", "singe/Framework already present at ${destFramework.absolutePath}")
            return
        }

        // Priority 1: use the stored DL2e base folder path (most reliable — avoids picking
        // the wrong FrameworkKimmy when multiple sibling game folders are present).
        val prefs = getSharedPreferences("hypseus_prefs", MODE_PRIVATE)
        val dl2ePath = prefs.getString("game_dl2e_base_folder_path", null)
        val dl2eFramework = dl2ePath?.let { File(it, "Framework").takeIf { f -> f.isDirectory } }

        val frameworkSource: File = dl2eFramework ?: run {
            // Fallback: search sibling directories for a Framework/ directory.
            // Prefer Framework/ over FrameworkKimmy/ to avoid picking the wrong engine version.
            val searchRoot = sdCardSearchRoot ?: run {
                Log.w("HypseusMain", "ensureSingeFramework: no DL2e pref and no search root")
                return
            }
            searchRoot.listFiles()
                ?.filter { it.isDirectory }
                ?.flatMap { sibling -> listOf(File(sibling, "Framework"), File(sibling, "FrameworkKimmy")) }
                ?.firstOrNull { it.isDirectory }
                ?: listOf(File(searchRoot, "Framework"), File(searchRoot, "FrameworkKimmy"))
                    .firstOrNull { it.isDirectory }
        } ?: run {
            Log.w("HypseusMain", "ensureSingeFramework: no Framework/ directory found")
            return
        }

        Log.d("HypseusMain", "ensureSingeFramework: copying from ${frameworkSource.absolutePath}")
        destFramework.mkdirs()
        // Recursive copy to handle subdirectories (e.g. Fonts/).
        frameworkSource.walkTopDown().forEach { src ->
            val rel = src.relativeTo(frameworkSource)
            val dest = File(destFramework, rel.path)
            when {
                src.isDirectory -> dest.mkdirs()
                !dest.exists() -> runCatching { src.copyTo(dest) }
                    .onFailure { Log.w("HypseusMain", "ensureSingeFramework: failed to copy ${src.name}: ${it.message}") }
            }
        }

        normalizeSingeFrameworkGlobals(File(destFramework, "globals.singe"))
    }

    private fun normalizeSingeFrameworkGlobals(globalsFile: File) {
        if (!globalsFile.isFile) return

        runCatching {
            val original = globalsFile.readText()
            var patched = original

            val addonLine = Regex("""(?m)^\s*dofile\(MYDIR\s*\.\.\s*\"/Script/addons\.singe\"\)\s*$""")
            val guardedAddonLine = Regex("""(?m)^\s*Tiers\s*=\s*Tiers\s+or\s+\{\}\s*$\r?\n^\s*dofile\(MYDIR\s*\.\.\s*\"/Script/addons\.singe\"\)\s*$""")
            if (!guardedAddonLine.containsMatchIn(patched)) {
                val addonMatch = addonLine.find(patched)
                if (addonMatch != null) {
                    patched = patched.replaceFirst(
                        addonMatch.value,
                        "Tiers = Tiers or {}\n${addonMatch.value.trim()}"
                    )
                }
            }

            patched = patched.replace(
                Regex("""(?m)^\s*Tiers\s*=\s*nil\s*$\r?\n^\s*Tiers\s*=\s*\{\}\s*$"""),
                "Tiers = Tiers or {}"
            )

            if (patched != original) {
                globalsFile.writeText(patched)
                Log.d("HypseusMain", "Normalized singe globals at ${globalsFile.absolutePath}")
            }
        }.onFailure {
            Log.w("HypseusMain", "normalizeSingeFrameworkGlobals: failed for ${globalsFile.absolutePath}: ${it.message}")
        }
    }

    private fun patchSingePathConstants(script: String, baseDir: String, gameDir: String?): String {
        val escapedBase = luaString(baseDir)
        val baseLine = "BASEDIR = \"$escapedBase\""

        var patched = script
        patched = if (Regex("""(?m)^\s*BASEDIR\s*=.*$""").containsMatchIn(patched)) {
            patched.replace(Regex("""(?m)^\s*BASEDIR\s*=.*$"""), baseLine)
        } else {
            "$baseLine\n$patched"
        }

        if (gameDir != null) {
            val escapedGame = luaString(gameDir)
            val gameLine = "MYDIR = \"$escapedGame\""
            patched = if (Regex("""(?m)^\s*MYDIR\s*=.*$""").containsMatchIn(patched)) {
                patched.replace(Regex("""(?m)^\s*MYDIR\s*=.*$"""), gameLine)
            } else {
                patched.replace(baseLine, "$baseLine\n$gameLine")
            }
        }

        return patched
    }

    private fun luaString(path: String): String {
        return path.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun persistSafPermission(uri: Uri) {
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, readWrite)
            return
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }

        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            Log.w("HypseusMain", "Unable to persist SAF read permission for $uri: ${e.message}")
        }
    }

    private fun savePickedUri(
        prefs: android.content.SharedPreferences,
        game: String,
        target: PickerTarget,
        uri: Uri,
    ) {
        prefs.edit()
            .putString(pickerUriPrefKey(game, target), uri.toString())
            .apply()
    }

    private fun pickerUriPrefKey(game: String, target: PickerTarget): String {
        return "game_${normalizeLaunchGameName(game)}_${target.name.lowercase()}_uri"
    }

    private fun buildStorageAccessReport(
        prefs: android.content.SharedPreferences,
        game: String,
        baseFolder: String,
        framefile: String,
        romDir: String,
        singeScript: String,
    ): String {
        val allFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
        val lines = mutableListOf("Storage access: all-files=$allFiles, media-read=${hasMediaReadAccess()}")
        lines += safReportLine(prefs, game, PickerTarget.BASE_FOLDER, "base", baseFolder, baseFolder)
        if (romDir.isNotBlank() && !isSingeGame(game)) {
            lines += safReportLine(prefs, game, PickerTarget.ROM_FOLDER, "rom", romDir, baseFolder)
        }
        lines += safReportLine(prefs, game, PickerTarget.FRAMEFILE_FILE, "framefile", framefile, baseFolder)
        if (singeScript.isNotBlank() && isSingeGame(game)) {
            lines += safReportLine(prefs, game, PickerTarget.SINGE_SCRIPT_FILE, "singe", singeScript, baseFolder)
        }
        return lines.joinToString("\n")
    }

    private fun safReportLine(
        prefs: android.content.SharedPreferences,
        game: String,
        target: PickerTarget,
        label: String,
        path: String,
        baseFolder: String,
    ): String {
        val uriString = prefs.getString(pickerUriPrefKey(game, target), null)
        if (uriString.isNullOrBlank()) {
            if (target != PickerTarget.BASE_FOLDER) {
                val baseUriString = prefs.getString(pickerUriPrefKey(game, PickerTarget.BASE_FOLDER), null)
                if (!baseUriString.isNullOrBlank() && isPathWithinBaseFolder(path, baseFolder)) {
                    val baseUri = Uri.parse(baseUriString)
                    val baseGrant = contentResolver.persistedUriPermissions.firstOrNull { it.uri == baseUri }
                    if (baseGrant?.isReadPermission == true) {
                        return "SAF $label: granted-via-base ($baseUriString)"
                    }
                }
            }
            return "SAF $label: no persisted picker grant recorded for $path"
        }

        val uri = Uri.parse(uriString)
        val grant = contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        val state = when {
            grant?.isReadPermission == true -> "granted"
            grant == null -> "missing"
            else -> "present-without-read"
        }
        return "SAF $label: $state ($uriString)"
    }

    private fun isPathWithinBaseFolder(path: String, baseFolder: String): Boolean {
        if (path.isBlank() || baseFolder.isBlank()) return false
        return runCatching {
            val base = File(baseFolder).canonicalFile
            val child = File(path).canonicalFile
            child.path == base.path || child.path.startsWith(base.path + File.separator)
        }.getOrElse {
            val normalizedBase = baseFolder.replace('\\', '/').trimEnd('/')
            val normalizedPath = path.replace('\\', '/')
            normalizedPath == normalizedBase || normalizedPath.startsWith("$normalizedBase/")
        }
    }

    private fun getPathFromUri(context: Context, uri: Uri): String? {
        if (DocumentsContract.isTreeUri(uri)) {
            return resolveExternalStoragePath(DocumentsContract.getTreeDocumentId(uri))
        }

        if (DocumentsContract.isDocumentUri(context, uri)) {
            return resolveExternalStoragePath(DocumentsContract.getDocumentId(uri))
        }

        if (uri.scheme.equals("file", ignoreCase = true)) {
            return uri.path
        }

        return null
    }

    private fun resolveExternalStoragePath(documentId: String): String? {
        val parts = documentId.split(":", limit = 2)
        if (parts.size < 2) return null

        val volumeId = parts[0]
        val relativePath = parts[1]
        return if (volumeId.equals("primary", ignoreCase = true)) {
            "/sdcard/$relativePath"
        } else {
            // Secondary storage (SD Card) is usually at /storage/VOLUME_ID/
            "/storage/$volumeId/$relativePath"
        }
    }

    private fun hasMediaReadAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return requiredMediaPermissions().all { permission ->
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

        return true
    }

    private fun requiredMediaPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VIDEO,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun getPreferredDefaultRootDir(): String {
        val candidates = if (BuildConfig.LOCKED_GAME_ID.equals("ace", ignoreCase = true)) {
            listOf(
                File("/storage/FEDD-B1FF/Hypseus/SAe"),
                File("/sdcard/Hypseus/SAe"),
                File("/sdcard/SAe"),
                File("/storage/0000-0000/SAe"),
                File("/storage/FEDD-B1FF/Hypseus/dragons_lair_classic"),
                File("/storage/FEDD-B1FF/Hypseus/DLe"),
            )
        } else {
            listOf(
                File("/storage/FEDD-B1FF/Hypseus/dragons_lair_classic"),
                File("/storage/FEDD-B1FF/Hypseus/DLe"),
                File("/sdcard/dragons_lair_classic"),
                File("/sdcard/DLe"),
                File("/storage/0000-0000/DLe"),
            )
        }
        for (dir in candidates) {
            if (dir.isDirectory) return dir.absolutePath
        }
        return (getExternalFilesDir(null) ?: filesDir).absolutePath
    }

    private fun guessDefaultGameName(rootDir: String): String {
        return when {
            rootDir.contains("dragons_lair_classic", ignoreCase = true) -> "dlclassic"
            rootDir.contains("DL2e", ignoreCase = false) -> "dl2e"
            rootDir.contains("SAe", ignoreCase = false) -> "sae"
            rootDir.contains("DLe", ignoreCase = false) -> "dle"
            shouldPreferSinge(rootDir) -> "dle"
            else -> "dle"
        }
    }

    private fun shouldPreferSinge(rootPath: String): Boolean {
        return rootPath.contains("/DLe", ignoreCase = false) ||
            rootPath.contains("\\DLe", ignoreCase = false) ||
            rootPath.contains("/DL2e", ignoreCase = false) ||
            rootPath.contains("\\DL2e", ignoreCase = false) ||
            rootPath.contains("/SAe", ignoreCase = false) ||
            rootPath.contains("\\SAe", ignoreCase = false) ||
            (hasLooseSingeScript(rootPath) && !rootPath.contains("dragons_lair_classic", ignoreCase = true))
    }

    private fun hasLooseSingeScript(rootPath: String): Boolean {
        val root = File(rootPath)
        if (!root.isDirectory) return false
        return listOf(
            File(root, "main.singe"),
            File(root, "amain.singe"),
            File(root, "${root.name}.singe"),
            File(root, "${root.name}/${root.name}.singe"),
            File(root, "DLe.singe"),
            File(root, "DLe/DLe.singe"),
            File(root, "DL2e/DL2e.singe"),
            File(root, "SAe/SAe.singe"),
            File(root, "lair.singe"),
        ).any { it.isFile }
    }

    private fun findSpaceAceSingeScript(baseFolder: String): File? {
        val base = File(baseFolder)
        if (!base.isDirectory) return null

        val candidates = listOf(
            File(base, "SAe/SAe.singe"),
            File(base, "SAe/sae.singe"),
            File(base, "sae.singe"),
            File(base, "spaceace/SAe.singe"),
            File(base, "spaceace/sae.singe"),
        )
        return candidates.firstOrNull { it.isFile }
    }





    private fun guessDefaultSingeScript(rootPath: String): String {
        val candidates = listOf(
            "$rootPath/main.singe",
            "$rootPath/amain.singe",
            "$rootPath/${File(rootPath).name}.singe",
            "$rootPath/${File(rootPath).name}/${File(rootPath).name}.singe",
            "$rootPath/DLe.zip",
            "$rootPath/lair.zip",
            "$rootPath/DLe.singe",
            "$rootPath/DLe/DLe.singe",
            "$rootPath/DL2e/DL2e.singe",
            "$rootPath/SAe/SAe.singe",
            "$rootPath/lair.singe",
            "$rootPath/singe/DLe.hypseus/DLe.zip",
            "$rootPath/singe/DLe/DLe.zip",
            "$rootPath/singe/DLe.hypseus/DLe.singe",
            "$rootPath/singe/DLe/DLe.singe",
            "$rootPath/singe/lair/lair.singe",
        )
        for (candidate in candidates) {
            if (File(candidate).isFile) return candidate
        }
        return candidates.first()
    }

    private fun guessDefaultFramefile(rootPath: String, game: String): String {
        val normalizedGame = normalizeLaunchGameName(game)
        val candidates = mutableListOf<String>()
        val extensions = listOf(".txt", ".m2x", ".m1")

        // Try base folder with both extensions
        for (ext in extensions) {
            candidates.add("$rootPath/${File(rootPath).name}$ext")
            candidates.add("$rootPath/${File(rootPath).name}/${File(rootPath).name}$ext")
            candidates.add("$rootPath/framefile$ext")
            candidates.add("$rootPath/main$ext")
        }

        // Try game-specific names with all extensions
        if (normalizedGame.isNotEmpty() && normalizedGame != "singe") {
            for (ext in extensions) {
                candidates.add("$rootPath/$normalizedGame$ext")
            }
        }

        // Try known game files with all extensions in root and game subfolders
        val knownGames = listOf("lair", "DLe", "DL2e", "SAe")
        for (game in knownGames) {
            for (ext in extensions) {
                candidates.add("$rootPath/$game$ext")
                candidates.add("$rootPath/$game/$game$ext")  // Look in game subfolder
                candidates.add("$rootPath/vldp/$game/$game$ext")
            }
        }

        for (candidate in candidates) {
            if (File(candidate).isFile) {
                return candidate
            }
        }
        return candidates.first()
    }



    private fun extractStorageRoot(path: String): String {
        // Extract storage root from paths like /storage/FEDD-B1FF/... or /sdcard/...
        return when {
            path.startsWith("/sdcard") -> "/sdcard"
            path.startsWith("/storage/") -> {
                val parts = path.split("/")
                if (parts.size >= 3) "/storage/${parts[2]}" else path
            }
            else -> path
        }
    }

    private fun resolveFramefilePath(
        game: String,
        requestedFramefile: String,
        baseFolder: String,
        romDir: String,
    ): String {
        val normalizedGame = game.trim().lowercase()
        val requested = requestedFramefile.trim()
        if (requested.isNotEmpty() && File(requested).isFile) {
            return requested
        }

        val candidates = mutableListOf<String>()

        // Keep old behavior for non-lair games: use requested path if provided.
        if (normalizedGame != "lair" && normalizedGame != "laireuro") {
            return requestedFramefile
        }

        if (requested.isNotEmpty()) {
            val requestedFile = File(requested)
            val requestedParent = requestedFile.parentFile
            if (requestedParent != null) {
                candidates.add(File(requestedParent, "lair.txt").absolutePath)
                candidates.add(File(requestedParent, "DLe.txt").absolutePath)
                candidates.add(File(requestedParent, "dragons_lair_classic.txt").absolutePath)

                if (requestedParent.name.equals("Video", ignoreCase = true)) {
                    val root = requestedParent.parentFile
                    if (root != null) {
                        candidates.add(File(root, "dragons_lair_classic.txt").absolutePath)
                        candidates.add(File(root, "DLe.txt").absolutePath)
                        candidates.add(File(root, "lair.txt").absolutePath)
                    }
                }
            }
        }

        val base = baseFolder.trim().removeSuffix("/")
        if (base.isNotEmpty()) {
            val baseDir = File(base)
            candidates.add(File(baseDir, "dragons_lair_classic.txt").absolutePath)
            candidates.add(File(baseDir, "DLe.txt").absolutePath)
            candidates.add(File(baseDir, "lair.txt").absolutePath)
            if (baseDir.name.equals("Video", ignoreCase = true)) {
                val parent = baseDir.parentFile
                if (parent != null) {
                    candidates.add(File(parent, "dragons_lair_classic.txt").absolutePath)
                    candidates.add(File(parent, "DLe.txt").absolutePath)
                    candidates.add(File(parent, "lair.txt").absolutePath)
                }
            }
        }

        val normalizedRomRoot = if (romDir.trim().removeSuffix("/").endsWith("/$normalizedGame", ignoreCase = true)) {
            File(romDir).parent
        } else {
            romDir
        }
        val romRoot = normalizedRomRoot?.trim()?.removeSuffix("/") ?: ""
        if (romRoot.isNotEmpty()) {
            val romDirFile = File(romRoot)
            candidates.add(File(romDirFile, "dragons_lair_classic.txt").absolutePath)
            candidates.add(File(romDirFile, "DLe.txt").absolutePath)
            candidates.add(File(romDirFile, "lair.txt").absolutePath)
        }

        for (candidate in candidates.distinct()) {
            if (File(candidate).isFile) {
                Log.d("HypseusMain", "resolveFramefilePath: using fallback framefile $candidate (requested=$requested)")
                return candidate
            }
        }

        return requestedFramefile
    }

    private fun defaultRootForGame(game: String, fallbackRoot: String): String {
        val normalizedGame = game.trim().lowercase()
        val gameSpecificCandidates = when (normalizedGame) {
            "dle" -> listOf(
                File("/storage/FEDD-B1FF/Hypseus/DLe"),
                File("/sdcard/DLe"),
                File("/storage/0000-0000/DLe"),
            )
            "dlclassic" -> listOf(
                File("/storage/FEDD-B1FF/Hypseus/dragons_lair_classic"),
                File("/sdcard/dragons_lair_classic"),
                File("/storage/0000-0000/dragons_lair_classic"),
            )
            "dl2e" -> listOf(
                File("/storage/FEDD-B1FF/Hypseus/DL2e"),
                File("/sdcard/DL2e"),
                File("/storage/0000-0000/DL2e"),
            )
            "sae" -> listOf(
                File("/storage/FEDD-B1FF/Hypseus/SAe"),
                File("/sdcard/SAe"),
                File("/storage/0000-0000/SAe"),
            )
            "laireuro" -> listOf(
                File("/sdcard/laireuro"),
                File("/storage/0000-0000/laireuro"),
            )
            else -> listOf(
                File("/sdcard/$normalizedGame"),
                File("/storage/0000-0000/$normalizedGame"),
            )
        }

        for (candidate in gameSpecificCandidates) {
            if (candidate.isDirectory) return candidate.absolutePath
        }
        return fallbackRoot
    }

    private fun loadGameSettings(
        prefs: android.content.SharedPreferences,
        game: String,
        fallbackRoot: String,
    ): GameSettings {
        val normalizedGame = normalizeLaunchGameName(game).ifEmpty { "dle" }
        val keyPrefix = "game_${normalizedGame}_"

        val defaultBase = defaultRootForGame(normalizedGame, fallbackRoot)
        val defaultFramefile = guessDefaultFramefile(defaultBase, normalizedGame)
        val defaultScript = if (isSingeGame(normalizedGame)) guessDefaultSingeScript(defaultBase) else ""

        val baseFolderPath = prefs.getString("${keyPrefix}base_folder_path", defaultBase) ?: defaultBase
        val framefilePath = prefs.getString("${keyPrefix}framefile_path", defaultFramefile) ?: defaultFramefile
        val romDirPath = prefs.getString("${keyPrefix}rom_dir_path", baseFolderPath) ?: baseFolderPath
        val configuredSingeScriptPath = prefs.getString("${keyPrefix}singe_script_path", defaultScript) ?: defaultScript
        val singeScriptPath = if (isSingeGame(normalizedGame)) {
            when {
                configuredSingeScriptPath.isNotBlank() && File(configuredSingeScriptPath).isFile -> configuredSingeScriptPath
                defaultScript.isNotBlank() && File(defaultScript).isFile -> defaultScript
                else -> configuredSingeScriptPath
            }
        } else {
            configuredSingeScriptPath
        }
        val digitalScoreboard = prefs.getBoolean("${keyPrefix}digital_scoreboard", false)
        val assistMode = prefs.getBoolean("${keyPrefix}assist_mode", false)
        val dipBankA = prefs.getString("${keyPrefix}dip_bank_a", "") ?: ""
        val dipBankB = prefs.getString("${keyPrefix}dip_bank_b", "") ?: ""
        val defaultAudioDelayMs = if (normalizedGame == "ace") "0" else ""
        val configuredAudioDelayMs = prefs.getString("${keyPrefix}audio_delay_ms", defaultAudioDelayMs) ?: defaultAudioDelayMs
        val audioDelayMs = if (configuredAudioDelayMs.isBlank()) defaultAudioDelayMs else configuredAudioDelayMs
        val effectiveFramefilePath = resolveFramefilePath(
            game = normalizedGame,
            requestedFramefile = framefilePath,
            baseFolder = baseFolderPath,
            romDir = romDirPath,
        )

        return GameSettings(
            baseFolderPath = baseFolderPath,
            framefilePath = effectiveFramefilePath,
            romDirPath = romDirPath,
            singeScriptPath = singeScriptPath,
            digitalScoreboard = digitalScoreboard,
            assistMode = assistMode,
            dipBankA = dipBankA,
            dipBankB = dipBankB,
            audioDelayMs = audioDelayMs
        )
    }

    private fun saveGameSettings(
        prefs: android.content.SharedPreferences,
        game: String,
        baseFolderPath: String,
        framefilePath: String,
        romDirPath: String,
        singeScriptPath: String,
        digitalScoreboard: Boolean,
        assistMode: Boolean,
        dipBankA: String,
        dipBankB: String,
        audioDelayMs: String = "",
    ) {
        val normalizedGame = normalizeLaunchGameName(game).ifEmpty { "dle" }
        val keyPrefix = "game_${normalizedGame}_"
        prefs.edit().apply {
            putString("${keyPrefix}base_folder_path", baseFolderPath)
            putString("${keyPrefix}framefile_path", framefilePath)
            putString("${keyPrefix}rom_dir_path", romDirPath)
            putString("${keyPrefix}singe_script_path", singeScriptPath)
            putBoolean("${keyPrefix}digital_scoreboard", digitalScoreboard)
            putBoolean("${keyPrefix}assist_mode", assistMode)
            putString("${keyPrefix}dip_bank_a", dipBankA)
            putString("${keyPrefix}dip_bank_b", dipBankB)
            putString("${keyPrefix}audio_delay_ms", audioDelayMs)
            putString("selected_game_name", normalizedGame)
        }.apply()
    }

    private fun migrateDragonLairGameEntries(
        prefs: android.content.SharedPreferences,
        fallbackRoot: String,
    ) {
        val didV1 = prefs.getBoolean("dragon_lair_named_entries_v1", false)

        fun copyLegacySinge(fromGame: String, toGame: String, fallbackBase: String) {
            val fromPrefix = "game_${fromGame}_"
            val toPrefix = "game_${toGame}_"
            if (prefs.contains("${toPrefix}base_folder_path")) return

            val base = prefs.getString("${fromPrefix}base_folder_path", fallbackBase) ?: fallbackBase
            val framefile = prefs.getString("${fromPrefix}framefile_path", guessDefaultFramefile(base, toGame))
                ?: guessDefaultFramefile(base, toGame)
            val script = prefs.getString("${fromPrefix}singe_script_path", guessDefaultSingeScript(base))
                ?: guessDefaultSingeScript(base)
            prefs.edit().apply {
                putString("${toPrefix}base_folder_path", base)
                putString("${toPrefix}framefile_path", framefile)
                putString("${toPrefix}rom_dir_path", base)
                putString("${toPrefix}singe_script_path", script.ifBlank { guessDefaultSingeScript(base) })
                putBoolean("${toPrefix}digital_scoreboard", prefs.getBoolean("${fromPrefix}digital_scoreboard", false))
                putBoolean("${toPrefix}assist_mode", prefs.getBoolean("${fromPrefix}assist_mode", false))
                putString("${toPrefix}dip_bank_a", prefs.getString("${fromPrefix}dip_bank_a", "") ?: "")
                putString("${toPrefix}dip_bank_b", prefs.getString("${fromPrefix}dip_bank_b", "") ?: "")
            }.apply()
        }

        if (!didV1) {
            val dleDefault = defaultRootForGame("dle", fallbackRoot)
            val classicDefault = defaultRootForGame("dlclassic", fallbackRoot)
            copyLegacySinge("singe", "dle", dleDefault)
            copyLegacySinge("lair", "dlclassic", classicDefault)

            val selected = normalizeLaunchGameName(prefs.getString("selected_game_name", "dle") ?: "dle")
            prefs.edit().apply {
                putString("selected_game_name", if (selected == "dlclassic") "dlclassic" else "dle")
                remove("game_name")
                for (legacy in listOf("game_singe_", "game_lair_")) {
                    remove("${legacy}base_folder_path")
                    remove("${legacy}framefile_path")
                    remove("${legacy}rom_dir_path")
                    remove("${legacy}singe_script_path")
                    remove("${legacy}digital_scoreboard")
                    remove("${legacy}assist_mode")
                    remove("${legacy}dip_bank_a")
                    remove("${legacy}dip_bank_b")
                    remove("${legacy}base_folder_uri")
                    remove("${legacy}framefile_file_uri")
                    remove("${legacy}rom_folder_uri")
                    remove("${legacy}singe_script_file_uri")
                }
                putBoolean("dragon_lair_named_entries_v1", true)
            }.apply()
        }

        if (!prefs.getBoolean("dragon_lair_extra_singe_entries_v1", false)) {
            ensureDefaultSingeEntry(prefs, "dl2e", fallbackRoot)
            ensureDefaultSingeEntry(prefs, "sae", fallbackRoot)
            prefs.edit().putBoolean("dragon_lair_extra_singe_entries_v1", true).apply()
        }
    }

    private fun ensureDefaultSingeEntry(
        prefs: android.content.SharedPreferences,
        game: String,
        fallbackRoot: String,
    ) {
        val normalizedGame = normalizeLaunchGameName(game)
        val keyPrefix = "game_${normalizedGame}_"
        if (prefs.contains("${keyPrefix}base_folder_path")) return

        val base = defaultRootForGame(normalizedGame, fallbackRoot)
        if (!File(base).isDirectory) return
        val framefile = guessDefaultFramefile(base, normalizedGame)
        val script = guessDefaultSingeScript(base)
        if (!File(framefile).isFile || !File(script).isFile) return

        prefs.edit().apply {
            putString("${keyPrefix}base_folder_path", base)
            putString("${keyPrefix}framefile_path", framefile)
            putString("${keyPrefix}rom_dir_path", base)
            putString("${keyPrefix}singe_script_path", script)
            putBoolean("${keyPrefix}digital_scoreboard", false)
            putBoolean("${keyPrefix}assist_mode", true)
            putString("${keyPrefix}dip_bank_a", "")
            putString("${keyPrefix}dip_bank_b", "")
        }.apply()
    }

    private fun knownGameNames(): List<String> = listOf(
        "dle",
        "dlclassic",
        "dl2e",
        "sae",
        "laireuro", "lair2",
        "ace",
        "tq",
        "astron", "badlands", "bega", "cliff",
        "cobraconv", "esh", "ffr", "firefox",
        "gpworld", "interstellar", "lgp", "mach3",
        "sdq", "starrider", "timetrav",
    )

    private fun resetWizardForNewInstall(prefs: android.content.SharedPreferences): Boolean {
        // Locked single-game builds should not force the setup wizard on every APK update.
        // We still allow the normal permission checks to drive the UI state.
        if (BuildConfig.LOCK_GAME_SELECTION) {
            return false
        }

        // Check if any picker URIs are saved - if none, it's a fresh install
        val hasSavedUris = prefs.all.keys.any { it.contains("_uri") }
        if (!hasSavedUris) {
            // First time or fresh install: clear wizard_done to force permission screens
            prefs.edit()
                .putBoolean("wizard_done", false)
                .apply()
            Log.d("HypseusMain", "resetWizardForNewInstall: No saved picker URIs, forcing wizard restart")
            return true
        }
        
        // Also check install timestamp as backup
        val packageInfo = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (e: Exception) {
            return false
        }
        
        val currentInstallStamp = packageInfo.lastUpdateTime
        val storedInstallStamp = prefs.getLong("wizard_last_install_stamp", -1L)
        if (storedInstallStamp != currentInstallStamp) {
            prefs.edit()
                .putBoolean("wizard_done", false)
                .putLong("wizard_last_install_stamp", currentInstallStamp)
                .apply()
            Log.d("HypseusMain", "resetWizardForNewInstall: Install stamp changed, forcing wizard restart")
            return true
        }
        
        return false
    }

    private fun lockedGameNameOrNull(): String? {
        if (!BuildConfig.LOCK_GAME_SELECTION) return null
        val normalized = normalizeLaunchGameName(BuildConfig.LOCKED_GAME_ID)
        return normalized.ifBlank { null }
    }

    private fun availableGameNames(lockedGameName: String?): List<String> {
        return if (lockedGameName != null) {
            listOf(lockedGameName)
        } else {
            knownGameNames()
        }
    }

    private fun loadConfiguredGames(
        prefs: android.content.SharedPreferences,
        selectedGame: String,
        lockedGameName: String?,
    ): List<String> {
        if (lockedGameName != null) {
            return listOf(lockedGameName)
        }

        val configured = knownGameNames()
            .filter { isGameSetupSaved(prefs, it) }
            .toMutableList()
        val normalizedSelected = normalizeLaunchGameName(selectedGame).ifEmpty { "dle" }
        if (configured.isEmpty()) {
            configured += normalizedSelected
        } else if (normalizedSelected !in configured && isGameSetupSaved(prefs, normalizedSelected)) {
            configured += normalizedSelected
        }
        return configured.distinct()
    }

    private fun isGameSetupSaved(
        prefs: android.content.SharedPreferences,
        game: String,
    ): Boolean {
        val normalizedGame = normalizeLaunchGameName(game).ifEmpty { "dle" }
        val keyPrefix = "game_${normalizedGame}_"
        val hasCommonPaths =
            prefs.contains("${keyPrefix}base_folder_path") &&
                prefs.contains("${keyPrefix}framefile_path")
        return if (isSingeGame(normalizedGame)) {
            hasCommonPaths && prefs.contains("${keyPrefix}singe_script_path")
        } else {
            hasCommonPaths && prefs.contains("${keyPrefix}rom_dir_path")
        }
    }

    private data class GameSettings(
        val baseFolderPath: String,
        val framefilePath: String,
        val romDirPath: String,
        val singeScriptPath: String,
        val digitalScoreboard: Boolean,
        val assistMode: Boolean,
        val dipBankA: String,
        val dipBankB: String,
        val audioDelayMs: String,
    )

    private data class CleanupResult(
        val bytesFreed: Long,
        val freeInternalBytes: Long,
        val lowStorageCritical: Boolean,
    )

    private data class ValidationResult(
        val ok: Boolean,
        val message: String,
    )

    private data class SingeLaunchScript(
        val scriptPath: String,
        val singeDir: String,
    )

    private data class DetectedLayout(
        val framefile: String,
        val romDir: String,
        val singeScript: String,
        val reportLines: List<String>,
    )

    private enum class PickerTarget {
        BASE_FOLDER,
        ROM_FOLDER,
        FRAMEFILE_FILE,
        SINGE_SCRIPT_FILE,
    }

    private enum class WizardStep {
        NEED_MEDIA_PERM,
        NEED_ALL_FILES,
        SETUP_GAME,
        DONE,
    }




}
