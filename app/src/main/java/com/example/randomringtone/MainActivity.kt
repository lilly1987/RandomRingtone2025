package com.example.randomringtone

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.view.LayoutInflater
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.SeekBar
import android.widget.CheckBox
import android.widget.ImageButton
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import androidx.documentfile.provider.DocumentFile
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import android.widget.Toast
import java.io.OutputStreamWriter
import org.json.JSONObject
import androidx.core.view.GravityCompat
import org.json.JSONArray
import org.json.JSONException
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.content.Context
import android.media.RingtoneManager
import android.provider.Settings
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import android.Manifest
import android.os.Build
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.IntentFilter

class MainActivity : AppCompatActivity() {

    private val ringtoneFolders = mutableListOf<Uri>()
    private val alarmFolders = mutableListOf<Uri>()
    private val notificationFolders = mutableListOf<Uri>()
    
    // 각 탭별 별도 뷰 목록
    private val ringtoneDisplayList = mutableListOf<String>()
    private val alarmDisplayList = mutableListOf<String>()
    private val notificationDisplayList = mutableListOf<String>()
    private val ringtoneOriginalDisplayList = mutableListOf<String>()
    private val alarmOriginalDisplayList = mutableListOf<String>()
    private val notificationOriginalDisplayList = mutableListOf<String>()
    
    // 각 탭별 검색 쿼리와 필터 모드
    private var ringtoneSearchQuery = ""
    private var alarmSearchQuery = ""
    private var notificationSearchQuery = ""
    private var ringtoneFilterMode = FilterMode.ALL
    private var alarmFilterMode = FilterMode.ALL
    private var notificationFilterMode = FilterMode.ALL
    
    private enum class FilterMode { ALL, CHECKED, UNCHECKED }
    private lateinit var folderAdapter: FolderAdapter
    private lateinit var folderListView: ListView
    private lateinit var emptyMessageView: TextView
    private lateinit var loadingMessageView: TextView
    private lateinit var searchEditText: android.widget.EditText
    private lateinit var preferences: SharedPreferences
    private val categoryLoaded = mutableSetOf<FolderCategory>()
    private var currentCategory = FolderCategory.RINGTONE
    private val deleteSelectedPositions = mutableSetOf<Int>()
    private val folderCounts = mutableMapOf<Uri, Int>()
    private val fileDurationsMs = mutableMapOf<Uri, Long>()
    private val ringtoneSelected = mutableSetOf<Uri>()
    private val alarmSelected = mutableSetOf<Uri>()
    private val notificationSelected = mutableSetOf<Uri>()
    private var selectionMode = false
    private var mediaPlayer: MediaPlayer? = null
    private var autoScrollOnTrackChange = true
    private var autoPlayNext = true
    private var hideSearchBar = false
    private var hideFilterOptions = false
    private var drawerAdapter: RecyclerView.Adapter<RecyclerView.ViewHolder>? = null
    private val longPressHandlers = mutableMapOf<Int, Runnable>()
    private var isRefreshingCounts = false
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val playQueue = mutableListOf<Uri>()
    private var currentPlayIndex = -1
    private var currentlyPlayingUri: Uri? = null
    private lateinit var playerPanel: ConstraintLayout
    private lateinit var playerSeekBar: SeekBar
    private lateinit var playerCheckBox: CheckBox
    private lateinit var playerPreviousButton: ImageButton
    private lateinit var playerPauseButton: ImageButton
    private lateinit var playerPlayButton: ImageButton
    private lateinit var playerNextButton: ImageButton
    private lateinit var playerFileName: TextView
    private lateinit var playerTimeText: TextView
    private val uiHandler = Handler(Looper.getMainLooper())
    private var isSeekBarUserDragging = false
    private val progressUpdater = object : Runnable {
        override fun run() {
            if (mediaPlayer != null && !isSeekBarUserDragging) {
                updateSeekBar()
                updateTimeText()
                // 재생 중인 항목의 진행률 업데이트
                folderAdapter.notifyDataSetChanged()
                uiHandler.postDelayed(this, 500)
            }
        }
    }
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var pendingFolderCategory: FolderCategory? = null
    private val createDocumentLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { }
            exportDataToFile(it)
        }
    }
    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { }
            importDataFromFile(it)
        }
    }
    private val openPlaylistLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { }
            loadPlaylistFromFile(it)
        }
    }
    private val openFolderAllFilesLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            val category = pendingFolderCategory ?: currentCategory
            pendingFolderCategory = null
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) { }

            Thread {
                val addedUris = mutableListOf<Uri>()
                val root = DocumentFile.fromTreeUri(this, it)
                if (root != null) {
                    collectAudioUris(root, addedUris)
                }
                val totalCount = addedUris.size
                if (totalCount > 0) {
                    val targetList = when (category) {
                        FolderCategory.RINGTONE -> ringtoneFolders
                        FolderCategory.ALARM -> alarmFolders
                        FolderCategory.NOTIFICATION -> notificationFolders
                    }
                    var changed = false
                    var completedCount = 0
                    for (fileUri in addedUris) {
                        if (targetList.none { saved -> saved == fileUri }) {
                            targetList.add(fileUri)
                            changed = true
                        }
                        completedCount++
                    }
                    if (changed) {
                        runOnUiThread {
                            if (category == currentCategory) {
                                updateDisplayList()
                            }
                            persistFolders()
                        }
                    }
                }
            }.start()
        }
    }

    private val openFilesLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val currentList = getCurrentFolderList()
        val totalCount = uris.size
        var completedCount = 0
        var added = false
        
        Thread {
            for (uri in uris) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: SecurityException) { }
                if (currentList.none { it == uri }) {
                    currentList.add(uri)
                    added = true
                }
                completedCount++
            }
            runOnUiThread {
                if (added) {
                    updateDisplayList()
                    persistFolders()
                }
            }
        }.start()
    }

    private val openFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // 권한을 얻지 못해도 선택한 폴더는 배열에 추가한다.
            }

            val currentList = getCurrentFolderList()
            if (currentList.none { saved -> saved == it }) {
                currentList.add(it)
                updateDisplayList()
                persistFolders()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        preferences = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        autoScrollOnTrackChange = preferences.getBoolean(KEY_AUTO_SCROLL_ON_TRACK_CHANGE, true)
        autoPlayNext = preferences.getBoolean(KEY_AUTO_PLAY_NEXT, true)
        hideSearchBar = preferences.getBoolean(KEY_HIDE_SEARCH_BAR, false)
        hideFilterOptions = preferences.getBoolean(KEY_HIDE_FILTER_OPTIONS, false)

        emptyMessageView = findViewById(R.id.emptyMessage)
        loadingMessageView = findViewById(R.id.loadingMessage)
        folderListView = findViewById(R.id.folderListView)
        searchEditText = findViewById(R.id.searchEditText)
        
        // 플레이어 창 초기화
        playerPanel = findViewById(R.id.playerPanel)
        playerSeekBar = findViewById(R.id.playerSeekBar)
        playerCheckBox = findViewById(R.id.playerCheckBox)
        playerPreviousButton = findViewById(R.id.playerPreviousButton)
        playerPauseButton = findViewById(R.id.playerPauseButton)
        playerPlayButton = findViewById(R.id.playerPlayButton)
        playerNextButton = findViewById(R.id.playerNextButton)
        playerFileName = findViewById(R.id.playerFileName)
        playerTimeText = findViewById(R.id.playerTimeText)
        
        setupPlayerControls()

        folderAdapter = FolderAdapter()
        folderListView.adapter = folderAdapter
        // long click은 각 항목의 터치 리스너에서 처리 (1초 딜레이)
        folderListView.setOnItemLongClickListener { _, _, _, _ ->
            false // 기본 동작 비활성화
        }

        setupSearchFilter()
        setupFilterOptions()
        updateVisibilitySettings()

        val tabLayout = findViewById<TabLayout>(R.id.categoryTabs)
        
        // 탭 레이아웃은 항상 활성화 (로딩 중에도 사용 가능)
        tabLayout.isEnabled = true
        tabLayout.isClickable = true
        tabLayout.elevation = 12f // 다른 요소 위에 표시
        
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val category = when (tab.position) {
                    0 -> FolderCategory.RINGTONE
                    1 -> FolderCategory.ALARM
                    else -> FolderCategory.NOTIFICATION
                }
                switchToCategory(category)
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit

            override fun onTabReselected(tab: TabLayout.Tab?) {
                // 탭을 다시 눌렀을 때는 별도 동작 없음
            }
        })

        tabLayout.getTabAt(0)?.select()

        // 초기 로딩은 비동기로 처리 (각 탭별로 별도 스레드 할당, 병렬 처리)
        // 목록 뷰가 비어있으면 로딩 메시지 표시, 목록 뷰는 백그라운드로
        updateListViewVisibility()
        
        // 각 탭별로 별도 스레드 할당하여 병렬로 미리 준비
        Thread {
            loadCategoryFoldersAsync(KEY_RINGTONE_FOLDERS, ringtoneFolders, FolderCategory.RINGTONE)
            loadCachedCountsForCategory(FolderCategory.RINGTONE)
            loadCachedDurationsForCategory(FolderCategory.RINGTONE)
            restorePersistentSelectionsForCategory(FolderCategory.RINGTONE)
            prepareCategoryDisplayList(FolderCategory.RINGTONE)
            runOnUiThread {
                categoryLoaded.add(FolderCategory.RINGTONE)
                if (currentCategory == FolderCategory.RINGTONE) {
                    updateDisplayList()
                }
            }
            validateAndRemoveInvalidUrisAsync(ringtoneFolders)
        }.start()
        
        Thread {
            loadCategoryFoldersAsync(KEY_ALARM_FOLDERS, alarmFolders, FolderCategory.ALARM)
            loadCachedCountsForCategory(FolderCategory.ALARM)
            loadCachedDurationsForCategory(FolderCategory.ALARM)
            restorePersistentSelectionsForCategory(FolderCategory.ALARM)
            prepareCategoryDisplayList(FolderCategory.ALARM)
            runOnUiThread {
                categoryLoaded.add(FolderCategory.ALARM)
                if (currentCategory == FolderCategory.ALARM) {
                    updateDisplayList()
                }
            }
            validateAndRemoveInvalidUrisAsync(alarmFolders)
        }.start()
        
        Thread {
            loadCategoryFoldersAsync(KEY_NOTIFICATION_FOLDERS, notificationFolders, FolderCategory.NOTIFICATION)
            loadCachedCountsForCategory(FolderCategory.NOTIFICATION)
            loadCachedDurationsForCategory(FolderCategory.NOTIFICATION)
            restorePersistentSelectionsForCategory(FolderCategory.NOTIFICATION)
            prepareCategoryDisplayList(FolderCategory.NOTIFICATION)
            runOnUiThread {
                categoryLoaded.add(FolderCategory.NOTIFICATION)
                if (currentCategory == FolderCategory.NOTIFICATION) {
                    updateDisplayList()
                }
            }
            validateAndRemoveInvalidUrisAsync(notificationFolders)
        }.start()

        setupPhoneStateListener()
        requestNotificationListenerPermission()

        setupDrawerLayout()

        val menuButton = findViewById<android.widget.ImageButton>(R.id.menuButton)
        val drawerLayout = findViewById<DrawerLayout>(R.id.drawerLayout)
        val mainLayout = findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.main)
        
        // 메뉴 버튼은 항상 활성화 (로딩 중에도 사용 가능)
        menuButton.isEnabled = true
        menuButton.isClickable = true
        menuButton.isFocusable = true
        menuButton.elevation = 16f // 높은 elevation로 다른 요소 위에 표시
        
        // 레이아웃 완료 후 메뉴 버튼과 탭 레이아웃을 최상위로 이동
        menuButton.post {
            menuButton.bringToFront()
            mainLayout.bringChildToFront(menuButton)
        }
        
        // 탭 레이아웃도 최상위로 이동
        tabLayout.post {
            tabLayout.bringToFront()
            mainLayout.bringChildToFront(tabLayout)
        }
        
        menuButton.setOnClickListener {
            drawerLayout.openDrawer(androidx.core.view.GravityCompat.START)
        }
        
        // DrawerLayout은 항상 열 수 있도록 설정 (로딩 중에도 사용 가능)
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        
        // 메인 레이아웃이 터치를 차단하지 않도록 설정
        mainLayout.isClickable = false
        mainLayout.isFocusable = false
        
        // 로딩 메시지와 빈 메시지가 터치를 차단하지 않도록 설정
        loadingMessageView.setOnTouchListener { _, _ -> false }
        emptyMessageView.setOnTouchListener { _, _ -> false }
        
        // 모든 UI 요소가 로딩 중에도 사용 가능하도록 설정
        folderListView.isEnabled = true
        folderListView.isClickable = true
        searchEditText.isEnabled = true
        searchEditText.isClickable = true
        searchEditText.isFocusable = true
        
        // 필터 옵션도 활성화
        val filterRadioGroup = findViewById<android.widget.RadioGroup>(R.id.filterRadioGroup)
        filterRadioGroup.isEnabled = true
        filterRadioGroup.isClickable = true
        
        // 플레이어 패널도 활성화
        playerPanel.isEnabled = true
        playerPanel.isClickable = true
        
        // FAB 버튼들도 활성화
        findViewById<FloatingActionButton>(R.id.addFolderButton).isEnabled = true
        findViewById<FloatingActionButton>(R.id.addFolderButton).isClickable = true
        findViewById<FloatingActionButton>(R.id.deleteSelectedButton).isEnabled = true
        findViewById<FloatingActionButton>(R.id.deleteSelectedButton).isClickable = true
        findViewById<FloatingActionButton>(R.id.cancelSelectionButton).isEnabled = true
        findViewById<FloatingActionButton>(R.id.cancelSelectionButton).isClickable = true

        findViewById<FloatingActionButton>(R.id.addFolderButton).setOnClickListener {
            showAddChoiceDialog()
        }

        findViewById<FloatingActionButton>(R.id.deleteSelectedButton).setOnClickListener {
            if (selectionMode) {
                // 선택 삭제 확인만 바로 띄우기
                if (deleteSelectedPositions.isEmpty()) return@setOnClickListener
                val count = deleteSelectedPositions.size
                AlertDialog.Builder(this)
                    .setTitle(R.string.delete_selected_title)
                    .setMessage(getString(R.string.delete_selected_message, count))
                    .setPositiveButton(R.string.delete_folder_confirm) { _, _ ->
                        val currentList = getCurrentFolderList()
                        val urisToRemove = deleteSelectedPositions.mapNotNull { pos ->
                            getUriForDisplayPosition(pos)
                        }
                        for (uri in urisToRemove) {
                            currentList.remove(uri)
                        }
                        deleteSelectedPositions.clear()
                        selectionMode = false
                        updateDisplayList()
                        persistFolders()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                showTrashMenu()
            }
        }

        findViewById<FloatingActionButton>(R.id.cancelSelectionButton).setOnClickListener {
            if (selectionMode) {
                selectionMode = false
                deleteSelectedPositions.clear()
                folderAdapter.notifyDataSetChanged()
                updateEmptyState()
            }
        }
    }

    private fun loadCachedCountsForCategory(category: FolderCategory) {
        val cachedString = preferences.getString(KEY_FOLDER_COUNTS_CACHE, null) ?: return
        try {
            val json = JSONObject(cachedString)
            val targetList = when (category) {
                FolderCategory.RINGTONE -> ringtoneFolders
                FolderCategory.ALARM -> alarmFolders
                FolderCategory.NOTIFICATION -> notificationFolders
            }
            targetList.forEach { uri ->
                val key = uri.toString()
                if (json.has(key)) {
                    val count = json.optInt(key, 0)
                    if (count > 0) {
                        folderCounts[uri] = count
                    }
                }
            }
        } catch (e: Exception) {
            // 캐시 로드 실패 시 무시
        }
    }
    
    private fun loadCachedDurationsForCategory(category: FolderCategory) {
        val cachedString = preferences.getString(KEY_FILE_DURATIONS_CACHE, null) ?: return
        try {
            val json = JSONObject(cachedString)
            val targetList = when (category) {
                FolderCategory.RINGTONE -> ringtoneFolders
                FolderCategory.ALARM -> alarmFolders
                FolderCategory.NOTIFICATION -> notificationFolders
            }
            targetList.forEach { uri ->
                val key = uri.toString()
                if (json.has(key)) {
                    val duration = json.optLong(key, 0)
                    if (duration > 0) {
                        fileDurationsMs[uri] = duration
                    }
                }
            }
        } catch (e: Exception) {
            // 캐시 로드 실패 시 무시
        }
    }
    
    private fun restorePersistentSelectionsForCategory(category: FolderCategory) {
        val key = when (category) {
            FolderCategory.RINGTONE -> KEY_RINGTONE_SELECTED
            FolderCategory.ALARM -> KEY_ALARM_SELECTED
            FolderCategory.NOTIFICATION -> KEY_NOTIFICATION_SELECTED
        }
        val targetSet = when (category) {
            FolderCategory.RINGTONE -> ringtoneSelected
            FolderCategory.ALARM -> alarmSelected
            FolderCategory.NOTIFICATION -> notificationSelected
        }
        preferences.getString(key, null)?.let {
            try {
                val arr = JSONArray(it)
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, null) ?: continue
                    targetSet.add(Uri.parse(s))
                }
            } catch (_: Exception) { }
        }
    }
    
    private fun loadCachedCounts() {
        val cachedString = preferences.getString(KEY_FOLDER_COUNTS_CACHE, null) ?: return
        try {
            val json = JSONObject(cachedString)
            folderCounts.clear()
            json.keys().forEach { key ->
                val uri = Uri.parse(key)
                val count = json.optInt(key, 0)
                if (count > 0) {
                    folderCounts[uri] = count
                }
            }
        } catch (e: Exception) {
            // 캐시 로드 실패 시 무시
        }
    }
    
    private fun loadCachedDurations() {
        val cachedString = preferences.getString(KEY_FILE_DURATIONS_CACHE, null) ?: return
        try {
            val json = JSONObject(cachedString)
            fileDurationsMs.clear()
            json.keys().forEach { key ->
                val uri = Uri.parse(key)
                val duration = json.optLong(key, 0)
                if (duration > 0) {
                    fileDurationsMs[uri] = duration
                }
            }
        } catch (e: Exception) {
            // 캐시 로드 실패 시 무시
        }
    }
    
    private fun saveCachedCounts() {
        try {
            val json = JSONObject()
            folderCounts.forEach { (uri, count) ->
                json.put(uri.toString(), count)
            }
            preferences.edit().putString(KEY_FOLDER_COUNTS_CACHE, json.toString()).apply()
        } catch (e: Exception) {
            // 캐시 저장 실패 시 무시
        }
    }
    
    private fun saveCachedDurations() {
        try {
            val json = JSONObject()
            fileDurationsMs.forEach { (uri, duration) ->
                json.put(uri.toString(), duration)
            }
            preferences.edit().putString(KEY_FILE_DURATIONS_CACHE, json.toString()).apply()
        } catch (e: Exception) {
            // 캐시 저장 실패 시 무시
        }
    }

    private fun persistFolders() {
        preferences.edit()
            .putString(KEY_RINGTONE_FOLDERS, serializeFolders(ringtoneFolders))
            .putString(KEY_ALARM_FOLDERS, serializeFolders(alarmFolders))
            .putString(KEY_NOTIFICATION_FOLDERS, serializeFolders(notificationFolders))
            .apply()
        updateTabCounts()
    }

    private fun updateListViewVisibility() {
        val currentOriginalList = getCurrentOriginalDisplayList()
        val currentDisplayList = getCurrentDisplayList()
        
        // 목록 뷰의 내용물이 다 만들어지기 전까지는 로딩 메시지 표시, 목록 뷰는 백그라운드로
        if (currentOriginalList.isEmpty()) {
            // 아직 로딩 중: 로딩 메시지 표시, 목록 뷰 숨김
            loadingMessageView.text = getString(R.string.loading)
            loadingMessageView.visibility = View.VISIBLE
            folderListView.visibility = View.GONE
            emptyMessageView.visibility = View.GONE
        } else {
            // 로딩 완료: 목록 뷰 표시, 로딩 메시지 숨김
            loadingMessageView.visibility = View.GONE
            folderListView.visibility = View.VISIBLE
            // 빈 메시지는 displayList 기준으로 표시
            emptyMessageView.visibility = if (currentDisplayList.isEmpty()) View.VISIBLE else View.GONE
        }
    }
    
    private fun updateEmptyState() {
        val currentDisplayList = getCurrentDisplayList()
        val currentOriginalList = getCurrentOriginalDisplayList()
        
        // 목록 뷰의 내용물이 다 만들어지기 전까지는 로딩 메시지 표시
        if (currentOriginalList.isEmpty()) {
            // 아직 로딩 중: 로딩 메시지 표시, 목록 뷰 숨김
            loadingMessageView.text = getString(R.string.loading)
            loadingMessageView.visibility = View.VISIBLE
            folderListView.visibility = View.GONE
            emptyMessageView.visibility = View.GONE
        } else {
            // 로딩 완료: 목록 뷰 표시, 로딩 메시지 숨김
            loadingMessageView.visibility = View.GONE
            folderListView.visibility = View.VISIBLE
            emptyMessageView.visibility = if (currentDisplayList.isEmpty()) View.VISIBLE else View.GONE
        }
        
        val deleteFab = findViewById<FloatingActionButton>(R.id.deleteSelectedButton)
        val addFab = findViewById<FloatingActionButton>(R.id.addFolderButton)
        val cancelFab = findViewById<FloatingActionButton>(R.id.cancelSelectionButton)

        // visibility만 제어, 위치는 레이아웃에서 관리
        if (selectionMode) {
            deleteFab.visibility = if (currentDisplayList.isEmpty()) View.GONE else View.VISIBLE
            addFab.visibility = View.GONE
            cancelFab.visibility = if (currentDisplayList.isEmpty()) View.GONE else View.VISIBLE
        } else {
            deleteFab.visibility = if (currentDisplayList.isEmpty()) View.GONE else View.VISIBLE
            addFab.visibility = View.VISIBLE
            cancelFab.visibility = View.GONE
        }
    }

    private fun getCurrentFolderList(): MutableList<Uri> = when (currentCategory) {
        FolderCategory.RINGTONE -> ringtoneFolders
        FolderCategory.ALARM -> alarmFolders
        FolderCategory.NOTIFICATION -> notificationFolders
    }
    
    private fun getCurrentDisplayList(): MutableList<String> = when (currentCategory) {
        FolderCategory.RINGTONE -> ringtoneDisplayList
        FolderCategory.ALARM -> alarmDisplayList
        FolderCategory.NOTIFICATION -> notificationDisplayList
    }
    
    private fun getCurrentOriginalDisplayList(): MutableList<String> = when (currentCategory) {
        FolderCategory.RINGTONE -> ringtoneOriginalDisplayList
        FolderCategory.ALARM -> alarmOriginalDisplayList
        FolderCategory.NOTIFICATION -> notificationOriginalDisplayList
    }
    
    private fun getCurrentSearchQuery(): String = when (currentCategory) {
        FolderCategory.RINGTONE -> ringtoneSearchQuery
        FolderCategory.ALARM -> alarmSearchQuery
        FolderCategory.NOTIFICATION -> notificationSearchQuery
    }
    
    private fun setCurrentSearchQuery(query: String) {
        when (currentCategory) {
            FolderCategory.RINGTONE -> ringtoneSearchQuery = query
            FolderCategory.ALARM -> alarmSearchQuery = query
            FolderCategory.NOTIFICATION -> notificationSearchQuery = query
        }
    }
    
    private fun getCurrentFilterMode(): FilterMode = when (currentCategory) {
        FolderCategory.RINGTONE -> ringtoneFilterMode
        FolderCategory.ALARM -> alarmFilterMode
        FolderCategory.NOTIFICATION -> notificationFilterMode
    }
    
    private fun setCurrentFilterMode(mode: FilterMode) {
        when (currentCategory) {
            FolderCategory.RINGTONE -> ringtoneFilterMode = mode
            FolderCategory.ALARM -> alarmFilterMode = mode
            FolderCategory.NOTIFICATION -> notificationFilterMode = mode
        }
    }

    private fun updateDisplayList() {
        val currentList = getCurrentFolderList()
        val currentOriginalList = getCurrentOriginalDisplayList()
        val currentDisplayList = getCurrentDisplayList()
        
        // originalDisplayList는 이미 prepareCategoryDisplayList에서 준비됨
        // 여기서는 필터만 적용
        applySearchFilter()
        deleteSelectedPositions.clear()
        selectionMode = false
        folderAdapter.notifyDataSetChanged()
        
        // 목록 뷰 표시 상태 업데이트
        updateListViewVisibility()
        updateEmptyState()
        updateTabCounts()
        // 카운트는 이미 로드된 경우에만 새로고침
        if (categoryLoaded.contains(currentCategory)) {
            // 지연 로딩: UI가 먼저 표시된 후 백그라운드에서 카운트 계산
            refreshHandler.postDelayed({
                refreshCountsForCurrentCategory()
            }, 300) // 300ms 지연
        } else {
            // 처음 로드하는 경우에만 카운트 새로고침
            categoryLoaded.add(currentCategory)
            // 지연 로딩: UI가 먼저 표시된 후 백그라운드에서 카운트 계산
            refreshHandler.postDelayed({
                refreshCountsForCurrentCategory()
            }, 300) // 300ms 지연
        }
    }
    
    private fun switchToCategory(category: FolderCategory) {
        // 현재 탭의 검색 쿼리와 필터 모드 저장
        setCurrentSearchQuery(searchEditText.text.toString().lowercase())
        
        // 탭 전환
        val previousCategory = currentCategory
        currentCategory = category
        
        // 새 탭의 검색 쿼리와 필터 모드 복원
        searchEditText.setText(getCurrentSearchQuery())
        val filterRadioGroup = findViewById<android.widget.RadioGroup>(R.id.filterRadioGroup)
        when (getCurrentFilterMode()) {
            FilterMode.ALL -> filterRadioGroup.check(R.id.filterAll)
            FilterMode.CHECKED -> filterRadioGroup.check(R.id.filterChecked)
            FilterMode.UNCHECKED -> filterRadioGroup.check(R.id.filterUnchecked)
        }
        
        // 이미 로드된 카테고리는 재로딩하지 않고 캐시된 뷰 표시
        if (categoryLoaded.contains(category)) {
            // 검색 필터만 다시 적용
            applySearchFilter()
            folderAdapter.notifyDataSetChanged()
            updateListViewVisibility()
            updateEmptyState()
        } else {
            // 아직 로딩 중이면 대기 (prepareCategoryDisplayList가 완료될 때까지)
            // categoryLoaded는 prepareCategoryDisplayList 완료 후 추가됨
            // 여기서는 UI만 업데이트
            updateListViewVisibility()
            updateEmptyState()
        }
    }
    
    private fun updateTabCounts() {
        val tabLayout = findViewById<TabLayout>(R.id.categoryTabs)
        
        // Ringtone 탭
        val ringtoneStats = calculateCategoryStats(ringtoneFolders, ringtoneSelected)
        tabLayout.getTabAt(0)?.text = getString(R.string.tab_ringtone) + 
            "(${ringtoneStats.selectedAudioCount}/${ringtoneStats.totalAudioCount}#${ringtoneStats.selectedFolderCount}/${ringtoneStats.totalFolderCount})"
        
        // Alarm 탭
        val alarmStats = calculateCategoryStats(alarmFolders, alarmSelected)
        tabLayout.getTabAt(1)?.text = getString(R.string.tab_alarm) + 
            "(${alarmStats.selectedAudioCount}/${alarmStats.totalAudioCount}#${alarmStats.selectedFolderCount}/${alarmStats.totalFolderCount})"
        
        // Notification 탭
        val notificationStats = calculateCategoryStats(notificationFolders, notificationSelected)
        tabLayout.getTabAt(2)?.text = getString(R.string.tab_notification) + 
            "(${notificationStats.selectedAudioCount}/${notificationStats.totalAudioCount}#${notificationStats.selectedFolderCount}/${notificationStats.totalFolderCount})"
    }
    
    private data class CategoryStats(
        val selectedAudioCount: Int,
        val selectedFolderCount: Int,
        val totalAudioCount: Int,
        val totalFolderCount: Int
    )
    
    private fun calculateCategoryStats(folders: List<Uri>, selected: Set<Uri>): CategoryStats {
        var selectedAudioCount = 0
        var selectedFolderCount = 0
        var totalAudioCount = 0
        var totalFolderCount = 0 //folders.size
        
        for (uri in folders) {
            val asTree = DocumentFile.fromTreeUri(this, uri)
            val asSingle = DocumentFile.fromSingleUri(this, uri)
            
            val isSelected = selected.contains(uri)
            val isFile = asSingle != null && asSingle.isFile
            val isFolder = asTree != null && asTree.isDirectory
            
            if (isFile) {
                totalAudioCount++
                if (isSelected) selectedAudioCount++
            } else if (isFolder) {
                //val audioCount = folderCounts[uri] ?: 0
                //totalAudioCount += audioCount
                totalFolderCount++
                if (isSelected) selectedFolderCount++                
            }
        }
        
        return CategoryStats(selectedAudioCount, selectedFolderCount, totalAudioCount, totalFolderCount)
    }

    private fun setupSearchFilter() {
        searchEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.lowercase() ?: ""
                setCurrentSearchQuery(query)
                applySearchFilter()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun applySearchFilter() {
        val currentDisplayList = getCurrentDisplayList()
        val currentOriginalList = getCurrentOriginalDisplayList()
        val currentList = getCurrentFolderList()
        val selectedSet = getCurrentPersistentSelected()
        val searchQuery = getCurrentSearchQuery()
        val filterMode = getCurrentFilterMode()
        
        currentDisplayList.clear()
        
        // 필터 모드에 따라 필터링
        val filteredList = when (filterMode) {
            FilterMode.ALL -> currentOriginalList
            FilterMode.CHECKED -> {
                currentOriginalList.filter { name ->
                    val uri = currentList.find { getDisplayName(it) == name }
                    uri != null && selectedSet.contains(uri)
                }
            }
            FilterMode.UNCHECKED -> {
                currentOriginalList.filter { name ->
                    val uri = currentList.find { getDisplayName(it) == name }
                    uri == null || !selectedSet.contains(uri)
                }
            }
        }
        
        // 검색 쿼리 적용
        if (searchQuery.isEmpty()) {
            currentDisplayList.addAll(filteredList)
        } else {
            filteredList.forEach { name ->
                if (name.lowercase().contains(searchQuery)) {
                    currentDisplayList.add(name)
                }
            }
        }
        folderAdapter.notifyDataSetChanged()
        updateEmptyState()
    }
    
    private fun setupFilterOptions() {
        val filterRadioGroup = findViewById<android.widget.RadioGroup>(R.id.filterRadioGroup)
        val filterAll = findViewById<android.widget.RadioButton>(R.id.filterAll)
        val filterChecked = findViewById<android.widget.RadioButton>(R.id.filterChecked)
        val filterUnchecked = findViewById<android.widget.RadioButton>(R.id.filterUnchecked)
        
        filterRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.filterAll -> FilterMode.ALL
                R.id.filterChecked -> FilterMode.CHECKED
                R.id.filterUnchecked -> FilterMode.UNCHECKED
                else -> FilterMode.ALL
            }
            setCurrentFilterMode(mode)
            applySearchFilter()
        }
    }
    
    private fun updateVisibilitySettings() {
        val filterRadioGroup = findViewById<android.widget.RadioGroup>(R.id.filterRadioGroup)
        filterRadioGroup.visibility = if (hideFilterOptions) View.GONE else View.VISIBLE
        searchEditText.visibility = if (hideSearchBar) View.GONE else View.VISIBLE
        
        // 레이아웃 제약 조건 업데이트
        val constraintSet = androidx.constraintlayout.widget.ConstraintSet()
        constraintSet.clone(findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.main))
        
        // ListView 상단 제약 조건 업데이트
        val listViewTopConstraint = if (hideFilterOptions) R.id.categoryTabs else R.id.filterRadioGroup
        constraintSet.connect(R.id.folderListView, androidx.constraintlayout.widget.ConstraintSet.TOP, listViewTopConstraint, androidx.constraintlayout.widget.ConstraintSet.BOTTOM)
        
        // ListView 하단 제약 조건 업데이트
        val listViewBottomConstraint = if (hideSearchBar) R.id.playerPanel else R.id.searchEditText
        constraintSet.connect(R.id.folderListView, androidx.constraintlayout.widget.ConstraintSet.BOTTOM, listViewBottomConstraint, androidx.constraintlayout.widget.ConstraintSet.TOP)
        
        // emptyMessage와 loadingMessage 제약 조건도 업데이트
        constraintSet.connect(R.id.emptyMessage, androidx.constraintlayout.widget.ConstraintSet.TOP, listViewTopConstraint, androidx.constraintlayout.widget.ConstraintSet.BOTTOM)
        constraintSet.connect(R.id.loadingMessage, androidx.constraintlayout.widget.ConstraintSet.TOP, listViewTopConstraint, androidx.constraintlayout.widget.ConstraintSet.BOTTOM)
        
        constraintSet.applyTo(findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.main))
    }

    private fun getUriForDisplayPosition(position: Int): Uri? {
        val currentDisplayList = getCurrentDisplayList()
        if (position !in currentDisplayList.indices) return null
        val displayName = currentDisplayList[position]
        val currentList = getCurrentFolderList()
        return currentList.find { getDisplayName(it) == displayName }
    }

    private fun getDisplayName(uri: Uri): String = uri.lastPathSegment ?: uri.toString()

    private fun validateAndRemoveInvalidUris(target: MutableList<Uri>) {
        val toRemove = mutableListOf<Uri>()
        for (uri in target) {
            val asTree = DocumentFile.fromTreeUri(this, uri)
            val asSingle = DocumentFile.fromSingleUri(this, uri)
            val exists = when {
                asTree != null -> asTree.exists()
                asSingle != null -> asSingle.exists()
                else -> false
            }
            if (!exists) {
                toRemove.add(uri)
            }
        }
        target.removeAll(toRemove)
        if (toRemove.isNotEmpty()) {
            persistFolders()
        }
    }
    
    private fun validateAndRemoveInvalidUrisAsync(target: MutableList<Uri>) {
        // 백그라운드에서 점진적으로 검증 (초기 로딩 속도 향상)
        Thread {
            val toRemove = mutableListOf<Uri>()
            var processedCount = 0
            val BATCH_SIZE = 10 // 한 번에 처리할 URI 수
            
            for (uri in target) {
                try {
                    val asTree = DocumentFile.fromTreeUri(this, uri)
                    val asSingle = DocumentFile.fromSingleUri(this, uri)
                    val exists = when {
                        asTree != null -> asTree.exists()
                        asSingle != null -> asSingle.exists()
                        else -> false
                    }
                    if (!exists) {
                        toRemove.add(uri)
                    }
                } catch (e: Exception) {
                    // 에러 발생 시 제거
                    toRemove.add(uri)
                }
                
                processedCount++
                // 배치 단위로 UI 업데이트
                if (processedCount % BATCH_SIZE == 0) {
                    if (toRemove.isNotEmpty()) {
                        runOnUiThread {
                            target.removeAll(toRemove)
                            toRemove.clear()
                            updateDisplayList()
                            persistFolders()
                        }
                    }
                    Thread.sleep(50) // CPU 부하 완화
                }
            }
            
            // 남은 항목 처리
            if (toRemove.isNotEmpty()) {
                runOnUiThread {
                    target.removeAll(toRemove)
                    if (toRemove.isNotEmpty()) {
                        updateDisplayList()
                        persistFolders()
                    }
                }
            }
        }.start()
    }

    private fun loadCategoryFolders(key: String, target: MutableList<Uri>) {
        target.clear()
        val savedString = preferences.getString(key, null) ?: return
        try {
            val jsonArray = JSONArray(savedString)
            for (i in 0 until jsonArray.length()) {
                val uriString = jsonArray.optString(i, null) ?: continue
                target.add(Uri.parse(uriString))
            }
        } catch (_: JSONException) {
            target.clear()
            preferences.edit().remove(key).apply()
        }
    }
    
    private fun loadCategoryFoldersAsync(key: String, target: MutableList<Uri>, category: FolderCategory) {
        target.clear()
        val savedString = preferences.getString(key, null) ?: return
        try {
            val jsonArray = JSONArray(savedString)
            val totalCount = jsonArray.length()
            
            // 모든 항목을 먼저 추가
            for (i in 0 until jsonArray.length()) {
                val uriString = jsonArray.optString(i, null) ?: continue
                val uri = Uri.parse(uriString)
                target.add(uri)
            }
        } catch (_: JSONException) {
            target.clear()
            preferences.edit().remove(key).apply()
        }
    }
    
    private fun prepareCategoryDisplayList(category: FolderCategory) {
        val target = when (category) {
            FolderCategory.RINGTONE -> ringtoneFolders
            FolderCategory.ALARM -> alarmFolders
            FolderCategory.NOTIFICATION -> notificationFolders
        }
        val originalList = when (category) {
            FolderCategory.RINGTONE -> ringtoneOriginalDisplayList
            FolderCategory.ALARM -> alarmOriginalDisplayList
            FolderCategory.NOTIFICATION -> notificationOriginalDisplayList
        }
        
        originalList.clear()
        
        // 항목이 1000개 이상이면 64개씩 배치로 나눠서 처리
        val BATCH_SIZE = 64
        val totalCount = target.size
        
        if (totalCount >= 128) {
            // 배치 단위로 처리
            val batchCount = (totalCount + BATCH_SIZE - 1) / BATCH_SIZE
            
            for (batchIndex in 0 until batchCount) {
                val startIndex = batchIndex * BATCH_SIZE
                val endIndex = minOf(startIndex + BATCH_SIZE, totalCount)
                
                // 각 배치를 별도 스레드에서 처리하거나 순차 처리
                val batchUris = target.subList(startIndex, endIndex)
                val batchNames = batchUris.map { getDisplayName(it) }
                
                // UI 스레드에서 배치 단위로 추가
                runOnUiThread {
                    originalList.addAll(batchNames)
                    // 현재 탭인 경우에만 UI 업데이트 (배치 처리 중에는 categoryLoaded에 아직 추가되지 않았으므로 체크하지 않음)
                    if (category == currentCategory) {
                        applySearchFilter()
                        folderAdapter.notifyDataSetChanged()
                        updateListViewVisibility()
                    }
                }
                
                // CPU 부하 완화를 위한 짧은 대기
                Thread.sleep(10)
            }
        } else {
            // 항목이 적으면 한 번에 처리
            val names = target.map { getDisplayName(it) }
            runOnUiThread {
                originalList.addAll(names)
                // 현재 탭인 경우에만 UI 업데이트
                if (category == currentCategory) {
                    applySearchFilter()
                    folderAdapter.notifyDataSetChanged()
                    updateListViewVisibility()
                }
            }
        }
    }

    private fun serializeFolders(source: List<Uri>): String {
        val jsonArray = JSONArray()
        source.forEach { jsonArray.put(it.toString()) }
        return jsonArray.toString()
    }

    private fun showDeleteDialog(position: Int) {
        val uri = getUriForDisplayPosition(position) ?: return
        val currentList = getCurrentFolderList()
        val currentDisplayList = getCurrentDisplayList()
        val folderName = currentDisplayList.getOrNull(position) ?: getString(R.string.delete_confirmation_default)

        AlertDialog.Builder(this)
            .setTitle(R.string.delete_folder_title)
            .setMessage(getString(R.string.delete_folder_message, folderName))
            .setPositiveButton(R.string.delete_folder_confirm) { _, _ ->
                currentList.remove(uri)
                updateDisplayList()
                persistFolders()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val PREF_NAME = "folder_prefs"
        private const val KEY_RINGTONE_FOLDERS = "selected_folders_ringtone"
        private const val KEY_ALARM_FOLDERS = "selected_folders_alarm"
        private const val KEY_NOTIFICATION_FOLDERS = "selected_folders_notification"
        private const val KEY_RINGTONE_SELECTED = "persistent_selected_ringtone"
        private const val KEY_ALARM_SELECTED = "persistent_selected_alarm"
        private const val KEY_NOTIFICATION_SELECTED = "persistent_selected_notification"
        private const val KEY_AUTO_SCROLL_ON_TRACK_CHANGE = "auto_scroll_on_track_change"
        private const val KEY_AUTO_PLAY_NEXT = "auto_play_next"
        private const val KEY_FOLDER_COUNTS_CACHE = "folder_counts_cache"
        private const val KEY_FILE_DURATIONS_CACHE = "file_durations_cache"
        private const val KEY_HIDE_SEARCH_BAR = "hide_search_bar"
        private const val KEY_HIDE_FILTER_OPTIONS = "hide_filter_options"
    }

    private enum class FolderCategory {
        RINGTONE, ALARM, NOTIFICATION
    }

    private inner class FolderAdapter : ArrayAdapter<String>(this@MainActivity, 0, getCurrentDisplayList()) {
        override fun getCount(): Int {
            return getCurrentDisplayList().size
        }
        
        override fun getItem(position: Int): String? {
            val list = getCurrentDisplayList()
            return if (position in list.indices) list[position] else null
        }
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_folder, parent, false)
            val titleView = view.findViewById<TextView>(R.id.text1)
            val checkBox = view.findViewById<android.widget.CheckBox>(R.id.checkBox)
            val countView = view.findViewById<TextView>(R.id.textCount)
            val container = view.findViewById<android.widget.LinearLayout>(R.id.itemRoot)
            val progress = view.findViewById<View>(R.id.progressFill)

            val currentDisplayList = getCurrentDisplayList()
            if (position !in currentDisplayList.indices) return view
            titleView.text = currentDisplayList[position]

            checkBox.setOnCheckedChangeListener(null)
            val rowUriForCheck = getUriForDisplayPosition(position)
            if (selectionMode) {
                checkBox.isChecked = deleteSelectedPositions.contains(position)
            } else {
                checkBox.isChecked = rowUriForCheck != null && getCurrentPersistentSelected().contains(rowUriForCheck)
            }
            checkBox.visibility = View.VISIBLE
                    checkBox.setOnCheckedChangeListener { _, isChecked ->
                        if (selectionMode) {
                            if (isChecked) deleteSelectedPositions.add(position) else deleteSelectedPositions.remove(position)
                            updateEmptyState()
                        } else if (rowUriForCheck != null) {
                            val set = getCurrentPersistentSelected()
                            if (isChecked) set.add(rowUriForCheck) else set.remove(rowUriForCheck)
                            persistPersistentSelections()
                            // 필터 모드에 따라 목록 업데이트
                            applySearchFilter()
                            // 재생창의 체크박스와 동기화
                            if (rowUriForCheck == currentlyPlayingUri) {
                                playerCheckBox.isChecked = isChecked
                            }
                        }
                    }

            view.setOnClickListener {
                if (selectionMode) {
                    val newChecked = !checkBox.isChecked
                    checkBox.isChecked = newChecked
                } else {
                    val uri = getUriForDisplayPosition(position)
                    uri?.let { handleItemClickPlayback(it) }
                }
            }

            view.setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        if (!selectionMode) {
                            // 1초 후 삭제 다이얼로그 표시
                            val handler = Runnable {
                                showDeleteDialog(position)
                            }
                            longPressHandlers[position] = handler
                            uiHandler.postDelayed(handler, 1000) // 1초 = 1000ms
                        }
                        false
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        // 터치가 끝나면 대기 중인 핸들러 제거
                        longPressHandlers[position]?.let {
                            uiHandler.removeCallbacks(it)
                            longPressHandlers.remove(position)
                        }
                        false
                    }
                    else -> false
                }
            }
            
            view.setOnLongClickListener {
                // 기본 long click은 사용하지 않음 (터치 리스너에서 처리)
                false
            }

            val uri = getUriForDisplayPosition(position)
            val isCurrentlyPlaying = uri != null && uri == currentlyPlayingUri && mediaPlayer != null
            val isPlaying = mediaPlayer?.isPlaying == true
            
            if (uri != null) {
                val asTree = DocumentFile.fromTreeUri(context, uri)
                val asSingle = DocumentFile.fromSingleUri(context, uri)
                when {
                    asSingle != null && asSingle.isFile -> {
                        val duration = fileDurationsMs[uri]
                        countView.text = duration?.let { formatDuration(it) } ?: "…"
                        
                        // 재생 중인 항목 강조
                        if (isCurrentlyPlaying && isPlaying) {
                            // 테두리 추가
                            container.setBackgroundResource(R.drawable.border_playing)
                            // 진행률에 따라 회색 배경 채우기
                            val player = mediaPlayer
                            if (player != null && player.duration > 0) {
                                val progressPercent = (player.currentPosition * 100 / player.duration).coerceIn(0, 100)
                                val containerWidth = container.width
                                if (containerWidth > 0) {
                                    val lp = progress.layoutParams
                                    lp.width = (containerWidth * progressPercent / 100)
                                    progress.layoutParams = lp
                                } else {
                                    // 컨테이너 너비가 아직 측정되지 않은 경우 post 사용
                                    view.post {
                                        val measuredWidth = container.width
                                        if (measuredWidth > 0) {
                                            val lp = progress.layoutParams
                                            lp.width = (measuredWidth * progressPercent / 100)
                                            progress.layoutParams = lp
                                        }
                                    }
                                }
                            }
                        } else {
                            container.background = null
                            val lp = progress.layoutParams
                            lp.width = 0
                            progress.layoutParams = lp
                        }
                    }
                    asTree != null && asTree.isDirectory -> {
                        val count = folderCounts[uri]
                        countView.text = count?.toString() ?: "…"
                        
                        // 재생 중인 항목 강조 (폴더는 진행률 표시 안함)
                        if (isCurrentlyPlaying && isPlaying) {
                            container.setBackgroundResource(R.drawable.border_playing)
                        } else {
                            container.background = null
                        }
                        view.post {
                            val lp = progress.layoutParams
                            lp.width = 0
                            progress.layoutParams = lp
                        }
                    }
                    else -> {
                        countView.text = "…"
                        container.background = null
                        view.post {
                            val lp = progress.layoutParams
                            lp.width = 0
                            progress.layoutParams = lp
                        }
                    }
                }
            } else {
                countView.text = "…"
                container.background = null
                view.post {
                    val lp = progress.layoutParams
                    lp.width = 0
                    progress.layoutParams = lp
                }
            }


            return view
        }
    }

    private fun handleItemClickPlayback(uri: Uri) {
        // 현재 재생 중인 항목을 다시 클릭하면 일시정지/재생
        if (uri == currentlyPlayingUri && mediaPlayer != null) {
            if (mediaPlayer!!.isPlaying) {
                mediaPlayer!!.pause()
                updatePlayPauseButtons()
                stopProgressUpdater()
            } else {
                mediaPlayer!!.start()
                updatePlayPauseButtons()
                startProgressUpdater()
            }
            folderAdapter.notifyDataSetChanged()
            return
        }
        
        val asTree = DocumentFile.fromTreeUri(this, uri)
        val asSingle = DocumentFile.fromSingleUri(this, uri)
        
        if (asTree != null && asTree.isDirectory) {
            // 폴더인 경우: 폴더 안의 모든 음악 파일을 수집하여 랜덤으로 재생 목록에 추가
            Thread {
                val audioFiles = mutableListOf<Uri>()
                collectAudioUris(asTree, audioFiles)
                if (audioFiles.isNotEmpty()) {
                    runOnUiThread {
                        playQueue.clear()
                        audioFiles.shuffle()
                        playQueue.addAll(audioFiles)
                        currentPlayIndex = 0
                        playCurrentTrack()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "폴더에 음악 파일이 없습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        } else if (asSingle != null && asSingle.isFile) {
            // 파일인 경우: 재생 목록에 추가하고 재생
            playQueue.clear()
            playQueue.add(uri)
            currentPlayIndex = 0
            playCurrentTrack()
        }
    }
    
    private fun setupPlayerControls() {
        playerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && mediaPlayer != null) {
                    val duration = mediaPlayer!!.duration
                    if (duration > 0) {
                        val position = (progress * duration / 100)
                        mediaPlayer!!.seekTo(position)
                        // 항목의 회색 백그라운드 위치 동기화
                        folderAdapter.notifyDataSetChanged()
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeekBarUserDragging = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeekBarUserDragging = false
            }
        })
        
        playerCheckBox.setOnCheckedChangeListener { _, isChecked ->
            if (currentlyPlayingUri != null) {
                val set = getCurrentPersistentSelected()
                if (isChecked) {
                    set.add(currentlyPlayingUri!!)
                } else {
                    set.remove(currentlyPlayingUri!!)
                }
                persistPersistentSelections()
                // 필터 모드에 따라 목록 업데이트
                applySearchFilter()
                // 항목의 체크박스와 동기화
                folderAdapter.notifyDataSetChanged()
            }
        }
        
        playerPlayButton.setOnClickListener {
            if (mediaPlayer != null) {
                mediaPlayer!!.start()
                updatePlayPauseButtons()
                startProgressUpdater()
            }
        }
        
        playerPauseButton.setOnClickListener {
            if (mediaPlayer != null) {
                mediaPlayer!!.pause()
                updatePlayPauseButtons()
                stopProgressUpdater()
            }
        }
        
        playerPreviousButton.setOnClickListener {
            playPreviousItem()
        }
        
        playerNextButton.setOnClickListener {
            playNextItem()
        }
    }
    
    private fun scrollToPlayingItem() {
        if (currentlyPlayingUri == null || !autoScrollOnTrackChange) return
        
        val displayName = getDisplayName(currentlyPlayingUri!!)
        val currentDisplayList = getCurrentDisplayList()
        val position = currentDisplayList.indexOf(displayName)
        if (position >= 0) {
            // 항목이 화면 가운데로 오도록 스크롤
            val firstVisible = folderListView.firstVisiblePosition
            val lastVisible = folderListView.lastVisiblePosition
            val visibleCount = lastVisible - firstVisible
            val targetPosition = position - visibleCount / 2
            folderListView.setSelection(targetPosition.coerceAtLeast(0))
        }
    }
    
    private fun playNextItem() {
        if (currentlyPlayingUri == null) return
        
        val currentList = getCurrentFolderList()
        val currentIndex = currentList.indexOf(currentlyPlayingUri)
        if (currentIndex < 0) return
        
        val nextIndex = (currentIndex + 1) % currentList.size
        val nextUri = currentList[nextIndex]
        handleItemClickPlayback(nextUri)
    }
    
    private fun playPreviousItem() {
        if (currentlyPlayingUri == null) return
        
        val currentList = getCurrentFolderList()
        val currentIndex = currentList.indexOf(currentlyPlayingUri)
        if (currentIndex < 0) return
        
        val prevIndex = (currentIndex - 1 + currentList.size) % currentList.size
        val prevUri = currentList[prevIndex]
        handleItemClickPlayback(prevUri)
    }
    
    private fun playCurrentTrack() {
        if (currentPlayIndex < 0 || currentPlayIndex >= playQueue.size) return
        
        val uri = playQueue[currentPlayIndex]
        currentlyPlayingUri = uri
        
        // 파일명 업데이트
        playerFileName.text = getDisplayName(uri)
        
        // 체크박스 상태 업데이트
        playerCheckBox.isChecked = getCurrentPersistentSelected().contains(uri)
        
        // 이전 미디어 플레이어 정리
        stopProgressUpdater()
        mediaPlayer?.release()
        mediaPlayer = null
        
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setDataSource(this@MainActivity, uri)
            setOnPreparedListener {
                it.start()
                updatePlayPauseButtons()
                updateSeekBar()
                updateTimeText()
                startProgressUpdater()
                // 재생 중인 항목 강조를 위해 adapter 업데이트
                folderAdapter.notifyDataSetChanged()
                // 플레이 중인 항목으로 자동 스크롤
                scrollToPlayingItem()
            }
            setOnCompletionListener {
                // 다음 항목 자동 재생 (옵션이 켜져있을 때만)
                if (autoPlayNext) {
                    playNextItem()
                } else {
                    // 자동 재생이 비활성화되어 있으면 재생만 중지
                    stopProgressUpdater()
                    updatePlayPauseButtons()
                    folderAdapter.notifyDataSetChanged()
                }
            }
            setOnErrorListener { _, what, extra ->
                stopProgressUpdater()
                updatePlayPauseButtons()
                folderAdapter.notifyDataSetChanged()
                true
            }
            prepareAsync()
        }
        
        // 재생 중인 항목 강조를 위해 adapter 업데이트
        folderAdapter.notifyDataSetChanged()
        // 플레이 중인 항목으로 자동 스크롤
        scrollToPlayingItem()
    }
    
    private fun updateSeekBar() {
        val player = mediaPlayer ?: return
        val duration = player.duration
        val position = player.currentPosition
        if (duration > 0) {
            playerSeekBar.max = 100
            playerSeekBar.progress = (position * 100 / duration)
        }
    }
    
    private fun updateTimeText() {
        val player = mediaPlayer ?: return
        val duration = player.duration
        val position = player.currentPosition
        if (duration > 0) {
            val currentTime = formatDuration(position.toLong())
            val totalTime = formatDuration(duration.toLong())
            playerTimeText.text = "$currentTime/$totalTime"
        } else {
            playerTimeText.text = "0:00/0:00"
        }
    }
    
    private fun updatePlayPauseButtons() {
        val isPlaying = mediaPlayer?.isPlaying == true
        playerPlayButton.visibility = if (isPlaying) View.GONE else View.VISIBLE
        playerPauseButton.visibility = if (isPlaying) View.VISIBLE else View.GONE
    }
    
    private fun startProgressUpdater() {
        uiHandler.removeCallbacks(progressUpdater)
        uiHandler.post(progressUpdater)
    }
    
    private fun stopProgressUpdater() {
        uiHandler.removeCallbacks(progressUpdater)
    }

    override fun onResume() {
        super.onResume()
        
        // 메뉴 버튼과 탭 레이아웃이 항상 최상위에 오도록 보장
        val menuButton = findViewById<android.widget.ImageButton>(R.id.menuButton)
        val tabLayout = findViewById<TabLayout>(R.id.categoryTabs)
        val mainLayout = findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.main)
        menuButton?.post {
            menuButton.bringToFront()
            mainLayout?.bringChildToFront(menuButton)
        }
        tabLayout?.post {
            tabLayout.bringToFront()
            mainLayout?.bringChildToFront(tabLayout)
        }
        
        // 앱이 다시 포그라운드로 돌아올 때 MediaPlayer 상태와 UI 동기화
        if (mediaPlayer != null) {
            updatePlayPauseButtons()
            updateSeekBar()
            updateTimeText()
            // 재생 중이면 progress updater 재시작
            if (mediaPlayer!!.isPlaying) {
                startProgressUpdater()
            }
            // 재생 중인 항목 강조 업데이트
            folderAdapter.notifyDataSetChanged()
        }
    }

    override fun onPause() {
        super.onPause()
        // 백그라운드로 갈 때 progress updater 중지 (MediaPlayer는 계속 재생)
        stopProgressUpdater()
    }

    override fun onStop() {
        super.onStop()
        // MediaPlayer는 onStop에서 release하지 않음 (백그라운드 재생 유지)
        // mediaPlayer?.release()
        // mediaPlayer = null
        stopProgressUpdater()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
        stopProgressUpdater()
        removePhoneStateListener()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setupPhoneStateListener()
        }
    }

    private fun setupPhoneStateListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), 100)
                return
            }
        }
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                super.onCallStateChanged(state, phoneNumber)
                if (state == TelephonyManager.CALL_STATE_RINGING) {
                    updateRandomRingtone()
                }
            }
        }
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun removePhoneStateListener() {
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        phoneStateListener = null
        telephonyManager = null
    }

    private fun getRandomAudioFileFromUri(uri: Uri): Uri? {
        val asTree = DocumentFile.fromTreeUri(this, uri)
        val asSingle = DocumentFile.fromSingleUri(this, uri)
        return when {
            asSingle != null && asSingle.isFile -> uri
            asTree != null && asTree.isDirectory -> {
                val audioFiles = mutableListOf<Uri>()
                collectAudioUris(asTree, audioFiles)
                audioFiles.randomOrNull()
            }
            else -> null
        }
    }

    private fun updateRandomRingtone() {
        if (ringtoneSelected.isEmpty()) return
        val selectedUri = ringtoneSelected.randomOrNull() ?: return
        val audioUri = getRandomAudioFileFromUri(selectedUri) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.System.canWrite(this)) {
                    // WRITE_SETTINGS 권한이 없으면 요청
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                    intent.data = android.net.Uri.parse("package:$packageName")
                    startActivity(intent)
                    return
                }
            }
            Settings.System.putString(contentResolver, Settings.System.RINGTONE, audioUri.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun checkAndUpdateAlarmRingtone() {
        if (alarmSelected.isEmpty()) return
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val nextAlarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.nextAlarmClock
            } else {
                null
            }
            // 알람이 설정되어 있으면 랜덤 알람 소리로 변경
            if (nextAlarm != null) {
                val selectedUri = alarmSelected.randomOrNull() ?: return
                val audioUri = getRandomAudioFileFromUri(selectedUri) ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (!Settings.System.canWrite(this)) {
                        return
                    }
                }
                Settings.System.putString(contentResolver, Settings.System.ALARM_ALERT, audioUri.toString())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun checkAndUpdateNotificationRingtone() {
        if (notificationSelected.isEmpty()) return
        try {
            val selectedUri = notificationSelected.randomOrNull() ?: return
            val audioUri = getRandomAudioFileFromUri(selectedUri) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.System.canWrite(this)) {
                    return
                }
            }
            Settings.System.putString(contentResolver, Settings.System.NOTIFICATION_SOUND, audioUri.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun requestNotificationListenerPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            val packageName = packageName
            if (enabledListeners == null || !enabledListeners.contains(packageName)) {
                // NotificationListenerService 활성화 안내
                AlertDialog.Builder(this)
                    .setTitle("알림 접근 권한 필요")
                    .setMessage("알림음 자동 변경 기능을 사용하려면 알림 접근 권한이 필요합니다.\n설정 화면에서 'RandomRingtone'을 활성화해주세요.")
                    .setPositiveButton("설정 열기") { _, _ ->
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        startActivity(intent)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun showTrashMenu() {
        val options = arrayOf(
            getString(R.string.delete_selected),
            getString(R.string.delete_all)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_menu_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> handleSelectDeleteFlow()
                    1 -> confirmDeleteAll()
                }
            }
            .show()
    }

    private fun handleSelectDeleteFlow() {
        if (!selectionMode) {
            selectionMode = true
            deleteSelectedPositions.clear()
            folderAdapter.notifyDataSetChanged()
            updateEmptyState()
            return
        }
        // selectionMode=true 인 상태에서 다시 선택 삭제를 누르면 실제 삭제 수행
        if (deleteSelectedPositions.isEmpty()) return
        val count = deleteSelectedPositions.size
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_selected_title)
            .setMessage(getString(R.string.delete_selected_message, count))
            .setPositiveButton(R.string.delete_folder_confirm) { _, _ ->
                val currentList = getCurrentFolderList()
                val sorted = deleteSelectedPositions.toList().sortedDescending()
                        for (pos in sorted) {
                            if (pos in currentList.indices) {
                                currentList.removeAt(pos)
                            }
                        }
                deleteSelectedPositions.clear()
                updateDisplayList()
                persistFolders()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteAll() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_all_title)
            .setMessage(R.string.delete_all_message)
            .setPositiveButton(R.string.delete_folder_confirm) { _, _ ->
                val currentList = getCurrentFolderList()
                currentList.clear()
                deleteSelectedPositions.clear()
                updateDisplayList()
                persistFolders()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshCountsForCurrentCategory() {
        if (isRefreshingCounts) return // 이미 새로고침 중이면 중복 실행 방지
        isRefreshingCounts = true
        
        val list = getCurrentFolderList().toList()
        if (list.isEmpty()) {
            isRefreshingCounts = false
            return
        }
        
        val totalCount = list.size
        
        Thread {
            var processedCount = 0
            var lastUpdateTime = System.currentTimeMillis()
            val BATCH_SIZE = 5 // 한 번에 처리할 항목 수
            val UPDATE_INTERVAL = 500L // UI 업데이트 간격 (ms)
            
            for (uri in list) {
                val asTree = DocumentFile.fromTreeUri(this, uri)
                val asSingle = DocumentFile.fromSingleUri(this, uri)
                
                if (asTree != null && asTree.isDirectory) {
                    if (!folderCounts.containsKey(uri)) {
                        // 폴더 카운트는 빠르게 처리 (재귀 탐색 최소화)
                        val count = try {
                            countAudioRecursively(uri)
                        } catch (e: Exception) {
                            0 // 에러 발생 시 0으로 설정
                        }
                        folderCounts[uri] = count
                    }
                } else if (asSingle != null && asSingle.isFile) {
                    if (!fileDurationsMs.containsKey(uri)) {
                        // duration 읽기는 느릴 수 있으므로 배치 처리
                        val duration = try {
                            readAudioDurationMs(uri)
                        } catch (e: Exception) {
                            null // 에러 발생 시 null
                        }
                        if (duration != null) {
                            fileDurationsMs[uri] = duration
                        }
                    }
                }
                
                processedCount++
                val currentTime = System.currentTimeMillis()
                
                // 배치 단위로 또는 일정 시간마다 UI 업데이트
                if (processedCount % BATCH_SIZE == 0 || (currentTime - lastUpdateTime) >= UPDATE_INTERVAL) {
                    runOnUiThread {
                        // 화면에 보이는 항목만 업데이트
                        val firstVisible = folderListView.firstVisiblePosition
                        val lastVisible = folderListView.lastVisiblePosition
                        if (firstVisible >= 0 && lastVisible >= firstVisible) {
                            folderAdapter.notifyDataSetChanged()
                        }
                    }
                    lastUpdateTime = currentTime
                }
                
                // 너무 오래 걸리면 중단 (최대 10초)
                if (currentTime - lastUpdateTime > 10000) {
                    break
                }
            }
            
                    runOnUiThread {
                        folderAdapter.notifyDataSetChanged()
                        isRefreshingCounts = false
                        // 캐시 저장
                        saveCachedCounts()
                        saveCachedDurations()
                    }
        }.start()
    }

    private fun getCurrentPersistentSelected(): MutableSet<Uri> = when (currentCategory) {
        FolderCategory.RINGTONE -> ringtoneSelected
        FolderCategory.ALARM -> alarmSelected
        FolderCategory.NOTIFICATION -> notificationSelected
    }

    private fun persistPersistentSelections() {
        val ring = JSONArray().apply { ringtoneSelected.forEach { put(it.toString()) } }.toString()
        val alarm = JSONArray().apply { alarmSelected.forEach { put(it.toString()) } }.toString()
        val notification = JSONArray().apply { notificationSelected.forEach { put(it.toString()) } }.toString()
        preferences.edit()
            .putString(KEY_RINGTONE_SELECTED, ring)
            .putString(KEY_ALARM_SELECTED, alarm)
            .putString(KEY_NOTIFICATION_SELECTED, notification)
            .apply()
    }

    private fun restorePersistentSelections() {
        ringtoneSelected.clear()
        alarmSelected.clear()
        notificationSelected.clear()
        preferences.getString(KEY_RINGTONE_SELECTED, null)?.let {
            try {
                val arr = JSONArray(it)
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, null) ?: continue
                    ringtoneSelected.add(Uri.parse(s))
                }
            } catch (_: Exception) { }
        }
        preferences.getString(KEY_ALARM_SELECTED, null)?.let {
            try {
                val arr = JSONArray(it)
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, null) ?: continue
                    alarmSelected.add(Uri.parse(s))
                }
            } catch (_: Exception) { }
        }
        preferences.getString(KEY_NOTIFICATION_SELECTED, null)?.let {
            try {
                val arr = JSONArray(it)
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, null) ?: continue
                    notificationSelected.add(Uri.parse(s))
                }
            } catch (_: Exception) { }
        }
    }

    private fun showAddChoiceDialog() {
        val options = arrayOf(
            getString(R.string.add_choice_folder),
            getString(R.string.add_choice_files),
            getString(R.string.add_choice_folder_all_files)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.add_choice_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startAddMultipleFoldersFlow()
                    1 -> openFilesLauncher.launch(arrayOf("audio/*"))
                    2 -> {
                        pendingFolderCategory = currentCategory
                        openFolderAllFilesLauncher.launch(null)
                    }
                }
            }
            .show()
    }

    private fun startAddMultipleFoldersFlow() {
        // 폴더는 SAF에서 다중선택이 불가하므로 한 개씩 선택 후 반복 여부를 물어본다.
        openFolderLauncher.launch(null)
        // 다음 선택 여부는 onActivityResult 흐름 이후에 묻기 위해 약간 지연해도 되고,
        // 여기서는 간단히 UI 갱신 후 다이얼로그로 계속 여부를 물어본다.
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(R.string.add_more_folders_title)
                .setMessage(R.string.add_more_folders_message)
                .setPositiveButton(R.string.add_choice_folder) { _, _ -> startAddMultipleFoldersFlow() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun countAudioRecursively(treeUri: Uri): Int {
        val root = DocumentFile.fromTreeUri(this, treeUri) ?: return 0
        return countAudioInDocument(root)
    }

    private fun countAudioInDocument(doc: DocumentFile): Int {
        if (!doc.canRead()) return 0
        if (doc.isFile) {
            // 파일명으로 먼저 빠르게 체크 (MIME 타입 체크는 느릴 수 있음)
            val name = doc.name?.lowercase() ?: return 0
            if (name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".m4a") ||
                name.endsWith(".aac") || name.endsWith(".ogg") || name.endsWith(".flac")) {
                return 1
            }
            // 파일명으로 판단 안되면 MIME 타입 체크
            val mime = doc.type
            if (mime != null && mime.startsWith("audio")) return 1
            return 0
        }
        if (doc.isDirectory) {
            var total = 0
            try {
                val children = doc.listFiles()
                // 너무 많은 파일이 있으면 제한 (성능 보호)
                val maxFiles = 10000
                var fileCount = 0
                for (child in children) {
                    if (fileCount++ > maxFiles) break
                    total += countAudioInDocument(child)
                }
            } catch (e: Exception) {
                // 에러 발생 시 0 반환
                return 0
            }
            return total
        }
        return 0
    }

    private fun collectAudioUris(doc: DocumentFile, out: MutableList<Uri>) {
        if (!doc.canRead()) return
        if (doc.isFile) {
            val mime = doc.type
            val name = doc.name?.lowercase()
            val isAudio = (mime != null && mime.startsWith("audio")) ||
                (name != null && (name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".m4a") ||
                name.endsWith(".aac") || name.endsWith(".ogg") || name.endsWith(".flac")))
            if (isAudio) out.add(doc.uri)
            return
        }
        if (doc.isDirectory) {
            for (child in doc.listFiles()) collectAudioUris(child, out)
        }
    }

    private fun readAudioDurationMs(uri: Uri): Long? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            durationStr?.toLongOrNull()
        } catch (e: Exception) {
            // 에러 발생 시 null 반환 (너무 자주 로그를 남기지 않음)
            null
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }
    private fun dpToPx(dp: Float): Float = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)

    private fun setupDrawerLayout() {
        val drawerLayout = findViewById<DrawerLayout>(R.id.drawerLayout)
        val navRecyclerView = findViewById<RecyclerView>(R.id.navRecyclerView)
        
        val menuItems = listOf(
            getString(R.string.export),
            getString(R.string.import1),
            getString(R.string.load_playlist),
            getString(R.string.manual),
            getString(R.string.auto_scroll_on_track_change) + if (autoScrollOnTrackChange) " (ON)" else " (OFF)",
            getString(R.string.auto_play_next) + if (autoPlayNext) " (ON)" else " (OFF)",
            getString(R.string.hide_search_bar) + if (hideSearchBar) " (ON)" else " (OFF)",
            getString(R.string.hide_filter_options) + if (hideFilterOptions) " (ON)" else " (OFF)"
        )
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.nav_drawer_item, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val textView = holder.itemView.findViewById<TextView>(R.id.navItemText)
                when (position) {
                    0 -> textView.text = getString(R.string.export)
                    1 -> textView.text = getString(R.string.import1)
                    2 -> textView.text = getString(R.string.load_playlist)
                    3 -> textView.text = getString(R.string.manual)
                    4 -> textView.text = getString(R.string.auto_scroll_on_track_change) + if (autoScrollOnTrackChange) " (ON)" else " (OFF)"
                    5 -> textView.text = getString(R.string.auto_play_next) + if (autoPlayNext) " (ON)" else " (OFF)"
                    6 -> textView.text = getString(R.string.hide_search_bar) + if (hideSearchBar) " (ON)" else " (OFF)"
                    7 -> textView.text = getString(R.string.hide_filter_options) + if (hideFilterOptions) " (ON)" else " (OFF)"
                    else -> textView.text = menuItems[position]
                }
                holder.itemView.setOnClickListener {
                    when (position) {
                        0 -> createDocumentLauncher.launch("RandomRingtone_backup.json")
                        1 -> openDocumentLauncher.launch(arrayOf("application/json", "*/*"))
                        2 -> openPlaylistLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        3 -> {
                            val intent = Intent(this@MainActivity, ManualActivity::class.java)
                            startActivity(intent)
                        }
                        4 -> {
                            // 곡 넘어갈때 자동 스크롤 토글
                            autoScrollOnTrackChange = !autoScrollOnTrackChange
                            preferences.edit().putBoolean(KEY_AUTO_SCROLL_ON_TRACK_CHANGE, autoScrollOnTrackChange).apply()
                            drawerAdapter?.notifyItemChanged(4)
                        }
                        5 -> {
                            // 곡 자동으로 넘기기 토글
                            autoPlayNext = !autoPlayNext
                            preferences.edit().putBoolean(KEY_AUTO_PLAY_NEXT, autoPlayNext).apply()
                            drawerAdapter?.notifyItemChanged(5)
                        }
                        6 -> {
                            // 하단 검색창 숨기기 토글
                            hideSearchBar = !hideSearchBar
                            preferences.edit().putBoolean(KEY_HIDE_SEARCH_BAR, hideSearchBar).apply()
                            drawerAdapter?.notifyItemChanged(6)
                            updateVisibilitySettings()
                        }
                        7 -> {
                            // 상단 옵션창 숨기기 토글
                            hideFilterOptions = !hideFilterOptions
                            preferences.edit().putBoolean(KEY_HIDE_FILTER_OPTIONS, hideFilterOptions).apply()
                            drawerAdapter?.notifyItemChanged(7)
                            updateVisibilitySettings()
                        }
                    }
                    drawerLayout.closeDrawer(GravityCompat.START)
                }
            }

            override fun getItemCount() = menuItems.size
        }
        
        navRecyclerView.layoutManager = LinearLayoutManager(this)
        navRecyclerView.adapter = adapter
        drawerAdapter = adapter
    }

    private fun exportDataToFile(uri: Uri) {
        try {
            val json = JSONObject()
            
            // 3개 탭의 목록 저장
            val ringtoneArray = JSONArray()
            ringtoneFolders.forEach { ringtoneArray.put(it.toString()) }
            json.put("ringtoneFolders", ringtoneArray)
            
            val alarmArray = JSONArray()
            alarmFolders.forEach { alarmArray.put(it.toString()) }
            json.put("alarmFolders", alarmArray)
            
            val notificationArray = JSONArray()
            notificationFolders.forEach { notificationArray.put(it.toString()) }
            json.put("notificationFolders", notificationArray)
            
            // 체크박스 선택 정보 저장
            val ringtoneSelectedArray = JSONArray()
            ringtoneSelected.forEach { ringtoneSelectedArray.put(it.toString()) }
            json.put("ringtoneSelected", ringtoneSelectedArray)
            
            val alarmSelectedArray = JSONArray()
            alarmSelected.forEach { alarmSelectedArray.put(it.toString()) }
            json.put("alarmSelected", alarmSelectedArray)
            
            val notificationSelectedArray = JSONArray()
            notificationSelected.forEach { notificationSelectedArray.put(it.toString()) }
            json.put("notificationSelected", notificationSelectedArray)
            
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream, "UTF-8").use { writer ->
                    writer.write(json.toString(2))
                }
            }
            
            Toast.makeText(this, R.string.export_success, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, R.string.export_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun importDataFromFile(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val jsonString = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONObject(jsonString)
                
                // 3개 탭의 목록 불러오기
                ringtoneFolders.clear()
                json.optJSONArray("ringtoneFolders")?.let { array ->
                    for (i in 0 until array.length()) {
                        val uriString = array.optString(i, null)
                        if (uriString != null) {
                            try {
                                val folderUri = Uri.parse(uriString)
                                ringtoneFolders.add(folderUri)
                            } catch (_: Exception) { }
                        }
                    }
                }
                
                alarmFolders.clear()
                json.optJSONArray("alarmFolders")?.let { array ->
                    for (i in 0 until array.length()) {
                        val uriString = array.optString(i, null)
                        if (uriString != null) {
                            try {
                                val folderUri = Uri.parse(uriString)
                                alarmFolders.add(folderUri)
                            } catch (_: Exception) { }
                        }
                    }
                }
                
                notificationFolders.clear()
                json.optJSONArray("notificationFolders")?.let { array ->
                    for (i in 0 until array.length()) {
                        val uriString = array.optString(i, null)
                        if (uriString != null) {
                            try {
                                val folderUri = Uri.parse(uriString)
                                notificationFolders.add(folderUri)
                            } catch (_: Exception) { }
                        }
                    }
                }
                
                // 체크박스 선택 정보 불러오기
                ringtoneSelected.clear()
                json.optJSONArray("ringtoneSelected")?.let { array ->
                    for (i in 0 until array.length()) {
                        val uriString = array.optString(i, null)
                        if (uriString != null) {
                            try {
                                val selectedUri = Uri.parse(uriString)
                                ringtoneSelected.add(selectedUri)
                            } catch (_: Exception) { }
                        }
                    }
                }
                
                alarmSelected.clear()
                json.optJSONArray("alarmSelected")?.let { array ->
                    for (i in 0 until array.length()) {
                        val uriString = array.optString(i, null)
                        if (uriString != null) {
                            try {
                                val selectedUri = Uri.parse(uriString)
                                alarmSelected.add(selectedUri)
                            } catch (_: Exception) { }
                        }
                    }
                }
                
                notificationSelected.clear()
                json.optJSONArray("notificationSelected")?.let { array ->
                    for (i in 0 until array.length()) {
                        val uriString = array.optString(i, null)
                        if (uriString != null) {
                            try {
                                val selectedUri = Uri.parse(uriString)
                                notificationSelected.add(selectedUri)
                            } catch (_: Exception) { }
                        }
                    }
                }
                
                // 유효하지 않은 URI 제거
                validateAndRemoveInvalidUris(ringtoneFolders)
                validateAndRemoveInvalidUris(alarmFolders)
                validateAndRemoveInvalidUris(notificationFolders)
                
                // 데이터 저장 및 UI 업데이트
                persistFolders()
                persistPersistentSelections()
                updateDisplayList()
                
                Toast.makeText(this, R.string.import_success, Toast.LENGTH_SHORT).show()
            } ?: run {
                Toast.makeText(this, R.string.import_error, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, R.string.import_invalid_file, Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadPlaylistFromFile(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val jsonString = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONObject(jsonString)
                
                // 재생목록은 URI 배열로 저장됨
                val playlistArray = json.optJSONArray("playlist") ?: json.optJSONArray("uris")
                if (playlistArray != null) {
                    playQueue.clear()
                    for (i in 0 until playlistArray.length()) {
                        val uriString = playlistArray.optString(i, null)
                        if (uriString != null) {
                            try {
                                val playlistUri = Uri.parse(uriString)
                                playQueue.add(playlistUri)
                            } catch (_: Exception) { }
                        }
                    }
                    if (playQueue.isNotEmpty()) {
                        currentPlayIndex = 0
                        playCurrentTrack()
                        Toast.makeText(this, "재생목록 ${playQueue.size}개 항목 불러오기 완료", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "재생목록이 비어있습니다.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "유효하지 않은 재생목록 파일입니다.", Toast.LENGTH_SHORT).show()
                }
            } ?: run {
                Toast.makeText(this, "파일을 읽을 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "재생목록 불러오기 실패", Toast.LENGTH_SHORT).show()
        }
    }
}