package com.writer.ui.documents

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.writer.ui.writing.WritingActivity

/**
 * Launcher activity. For now, immediately opens the writing canvas.
 * Document management will be added later.
 */
class DocumentListActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, WritingActivity::class.java))
        finish()
    }
}
