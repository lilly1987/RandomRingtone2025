package com.example.randomringtone

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.BufferedReader
import java.io.InputStreamReader

class ManualActivity : AppCompatActivity() {
    
    private lateinit var manualTextView: TextView
    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { }
            loadManualFromFile(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_manual)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            finish()
        }

        manualTextView = findViewById(R.id.manualTextView)
        
        // assets 폴더의 기본 파일 로드 시도
        try {
            val inputStream = assets.open("manual.txt")
            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
            val content = reader.readText()
            reader.close()
            inputStream.close()
            manualTextView.text = content.ifEmpty { "설명서 파일이 비어있습니다." }
        } catch (e: Exception) {
            // assets 파일이 없으면 사용자에게 파일 선택 요청
            manualTextView.text = "설명서 파일을 선택해주세요."
            openDocumentLauncher.launch(arrayOf("text/plain", "*/*"))
        }
    }

    private fun loadManualFromFile(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
                val content = reader.readText()
                manualTextView.text = content.ifEmpty { "설명서 파일이 비어있습니다." }
            } ?: run {
                manualTextView.text = "파일을 읽을 수 없습니다."
            }
        } catch (e: Exception) {
            e.printStackTrace()
            manualTextView.text = "파일을 읽는 중 오류가 발생했습니다: ${e.message}"
        }
    }
}

