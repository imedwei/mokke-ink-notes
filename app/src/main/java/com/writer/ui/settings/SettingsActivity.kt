package com.writer.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.writer.R
import com.writer.storage.DocumentStorage
import com.writer.storage.SearchIndexManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "writer_prefs"
        const val PREF_USE_WHISPER = "use_whisper_transcriber"
        const val PREF_TRANSCRIPTION_ENGINE = "transcription_engine"
        const val ENGINE_SYSTEM = "system"
        const val ENGINE_VOSK = "vosk"
        const val ENGINE_WHISPER = "whisper"
        const val ENGINE_SHERPA = "sherpa"
        const val PREF_SYNC_FOLDER = "sync_folder_uri"
        const val RESULT_SHOW_TUTORIAL = "show_tutorial"
        const val RESULT_DEBUG_RESET = "debug_reset"
    }

    private val pickSyncFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(PREF_SYNC_FOLDER, uri.toString()).apply()
            updateSyncFolderStatus()
            Toast.makeText(this, "Sync folder set", Toast.LENGTH_SHORT).show()

            GlobalScope.launch(Dispatchers.IO) {
                val count = DocumentStorage.restoreFromSyncFolder(this@SettingsActivity, uri)
                if (count > 0) {
                    SearchIndexManager.rebuildIndex(this@SettingsActivity)
                    runOnUiThread {
                        Toast.makeText(this@SettingsActivity, "Restored $count documents", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Transcription engine selector
        val engineGroup = findViewById<RadioGroup>(R.id.transcriptionEngineGroup)
        val currentEngine = prefs.getString(PREF_TRANSCRIPTION_ENGINE, ENGINE_SHERPA)
        when (currentEngine) {
            ENGINE_SHERPA -> engineGroup.check(R.id.radioSherpa)
            ENGINE_VOSK -> engineGroup.check(R.id.radioVosk)
            ENGINE_WHISPER -> engineGroup.check(R.id.radioWhisper)
            else -> engineGroup.check(R.id.radioSystem)
        }
        engineGroup.setOnCheckedChangeListener { _, checkedId ->
            val engine = when (checkedId) {
                R.id.radioSherpa -> ENGINE_SHERPA
                R.id.radioVosk -> ENGINE_VOSK
                R.id.radioWhisper -> ENGINE_WHISPER
                else -> ENGINE_SYSTEM
            }
            prefs.edit().putString(PREF_TRANSCRIPTION_ENGINE, engine).apply()
        }

        // Sync folder
        updateSyncFolderStatus()
        findViewById<android.view.View>(R.id.settingSyncFolder).setOnClickListener {
            pickSyncFolder.launch(null)
        }

        // Tutorial
        findViewById<android.view.View>(R.id.settingTutorial).setOnClickListener {
            setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_SHOW_TUTORIAL, true))
            finish()
        }

        // Debug reset
        findViewById<android.view.View>(R.id.settingDebugReset).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset All Data")
                .setMessage("This will delete all documents and reset all preferences. This cannot be undone.")
                .setPositiveButton("Reset") { _, _ ->
                    setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_DEBUG_RESET, true))
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun updateSyncFolderStatus() {
        val status = findViewById<TextView>(R.id.settingSyncFolderStatus)
        val uri = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_SYNC_FOLDER, null)
        status.text = if (uri != null) "Configured" else "Not configured"
    }
}
