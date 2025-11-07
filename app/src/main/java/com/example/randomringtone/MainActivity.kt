package com.example.randomringtone

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.view.LayoutInflater
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import androidx.documentfile.provider.DocumentFile
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
    private val displayList = mutableListOf<String>()
    private lateinit var folderAdapter: FolderAdapter
    private lateinit var folderListView: ListView
    private lateinit var emptyMessageView: TextView
    private lateinit var preferences: SharedPreferences
    private var currentCategory = FolderCategory.RINGTONE
    private val deleteSelectedPositions = mutableSetOf<Int>()
    private val folderCounts = mutableMapOf<Uri, Int>()
    private val fileDurationsMs = mutableMapOf<Uri, Long>()
    private val ringtoneSelected = mutableSetOf<Uri>()
    private val alarmSelected = mutableSetOf<Uri>()
    private val notificationSelected = mutableSetOf<Uri>()
    private val uiHandler = Handler(Looper.getMainLooper())
    private val progressUpdater = object : Runnable {
        override fun run() {
            if (mediaPlayer != null) {
                folderAdapter.notifyDataSetChanged()
                uiHandler.postDelayed(this, 500)
            }
        }
    }
    private var selectionMode = false
    private var mediaPlayer: MediaPlayer? = null
    private var currentlyPlayingUri: Uri? = null
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var pendingFolderCategory: FolderCategory? = null
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
                if (addedUris.isNotEmpty()) {
                    val targetList = when (category) {
                        FolderCategory.RINGTONE -> ringtoneFolders
                        FolderCategory.ALARM -> alarmFolders
                        FolderCategory.NOTIFICATION -> notificationFolders
                    }
                    var changed = false
                    for (fileUri in addedUris) {
                        if (targetList.none { saved -> saved == fileUri }) {
                            targetList.add(fileUri)
                            changed = true
                        }
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
        var added = false
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
        }
        if (added) {
            updateDisplayList()
            persistFolders()
        }
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

        emptyMessageView = findViewById(R.id.emptyMessage)
        folderListView = findViewById(R.id.folderListView)

        folderAdapter = FolderAdapter()
        folderListView.adapter = folderAdapter
        folderListView.setOnItemLongClickListener { _, _, position, _ ->
            showDeleteDialog(position)
            true
        }

        val tabLayout = findViewById<TabLayout>(R.id.categoryTabs)
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentCategory = when (tab.position) {
                    0 -> FolderCategory.RINGTONE
                    1 -> FolderCategory.ALARM
                    else -> FolderCategory.NOTIFICATION
                }
                updateDisplayList()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit

            override fun onTabReselected(tab: TabLayout.Tab?) {
                // 탭을 다시 눌렀을 때는 별도 동작 없음
            }
        })

        tabLayout.getTabAt(0)?.select()

        restoreFolders()
        restorePersistentSelections()

        setupPhoneStateListener()
        requestNotificationListenerPermission()

        findViewById<android.widget.ImageButton>(R.id.menuButton).setOnClickListener {
            // 메뉴 기능은 나중에 추가 가능
        }

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
                        val sorted = deleteSelectedPositions.toList().sortedDescending()
                        for (pos in sorted) {
                            if (pos in currentList.indices) {
                                val removed = currentList.removeAt(pos)
                                if (removed == currentlyPlayingUri) {
                                    mediaPlayer?.release()
                                    mediaPlayer = null
                                    currentlyPlayingUri = null
                                }
                            }
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

    private fun restoreFolders() {
        loadCategoryFolders(KEY_RINGTONE_FOLDERS, ringtoneFolders)
        loadCategoryFolders(KEY_ALARM_FOLDERS, alarmFolders)
        loadCategoryFolders(KEY_NOTIFICATION_FOLDERS, notificationFolders)
        validateAndRemoveInvalidUris(ringtoneFolders)
        validateAndRemoveInvalidUris(alarmFolders)
        validateAndRemoveInvalidUris(notificationFolders)
        updateDisplayList()
    }

    private fun persistFolders() {
        preferences.edit()
            .putString(KEY_RINGTONE_FOLDERS, serializeFolders(ringtoneFolders))
            .putString(KEY_ALARM_FOLDERS, serializeFolders(alarmFolders))
            .putString(KEY_NOTIFICATION_FOLDERS, serializeFolders(notificationFolders))
            .apply()
    }

    private fun updateEmptyState() {
        emptyMessageView.visibility = if (displayList.isEmpty()) View.VISIBLE else View.GONE
        val deleteFab = findViewById<FloatingActionButton>(R.id.deleteSelectedButton)
        val addFab = findViewById<FloatingActionButton>(R.id.addFolderButton)
        val cancelFab = findViewById<FloatingActionButton>(R.id.cancelSelectionButton)

        deleteFab.visibility = if (displayList.isEmpty()) View.GONE else View.VISIBLE
        if (selectionMode) {
            addFab.visibility = View.GONE
            cancelFab.visibility = if (displayList.isEmpty()) View.GONE else View.VISIBLE
        } else {
            addFab.visibility = View.VISIBLE
            cancelFab.visibility = View.GONE
        }
    }

    private fun getCurrentFolderList(): MutableList<Uri> = when (currentCategory) {
        FolderCategory.RINGTONE -> ringtoneFolders
        FolderCategory.ALARM -> alarmFolders
        FolderCategory.NOTIFICATION -> notificationFolders
    }

    private fun updateDisplayList() {
        val currentList = getCurrentFolderList()
        validateAndRemoveInvalidUris(currentList)
        displayList.clear()
        currentList.forEach { displayList.add(getDisplayName(it)) }
        deleteSelectedPositions.clear()
        selectionMode = false
        folderAdapter.notifyDataSetChanged()
        updateEmptyState()
        refreshCountsForCurrentCategory()
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

    private fun serializeFolders(source: List<Uri>): String {
        val jsonArray = JSONArray()
        source.forEach { jsonArray.put(it.toString()) }
        return jsonArray.toString()
    }

    private fun showDeleteDialog(position: Int) {
        val currentList = getCurrentFolderList()
        if (position !in currentList.indices) return

        val folderName = displayList.getOrNull(position) ?: getString(R.string.delete_confirmation_default)

        AlertDialog.Builder(this)
            .setTitle(R.string.delete_folder_title)
            .setMessage(getString(R.string.delete_folder_message, folderName))
            .setPositiveButton(R.string.delete_folder_confirm) { _, _ ->
                currentList.removeAt(position)
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
    }

    private enum class FolderCategory {
        RINGTONE, ALARM, NOTIFICATION
    }

    private inner class FolderAdapter : ArrayAdapter<String>(this@MainActivity, 0, displayList) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_folder, parent, false)
            val titleView = view.findViewById<TextView>(R.id.text1)
            val checkBox = view.findViewById<android.widget.CheckBox>(R.id.checkBox)
            val countView = view.findViewById<TextView>(R.id.textCount)
            val container = view.findViewById<android.widget.LinearLayout>(R.id.itemRoot)
            val progress = view.findViewById<View>(R.id.progressFill)

            titleView.text = displayList[position]

            checkBox.setOnCheckedChangeListener(null)
            val rowUriForCheck = getCurrentFolderList().getOrNull(position)
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
                }
            }

            view.setOnClickListener {
                if (selectionMode) {
                    val newChecked = !checkBox.isChecked
                    checkBox.isChecked = newChecked
                } else {
                    val uri = getCurrentFolderList().getOrNull(position)
                    uri?.let { handleItemClickPlayback(it) }
                }
            }

            view.setOnLongClickListener {
                if (!selectionMode) {
                    showDeleteDialog(position)
                    true
                } else false
            }

            val uri = getCurrentFolderList().getOrNull(position)
            if (uri != null) {
                val asTree = DocumentFile.fromTreeUri(context, uri)
                val asSingle = DocumentFile.fromSingleUri(context, uri)
                when {
                    asSingle != null && asSingle.isFile -> {
                        val duration = fileDurationsMs[uri]
                        countView.text = duration?.let { formatDuration(it) } ?: "…"
                        // 진행도 및 테두리
                        if (uri == currentlyPlayingUri && mediaPlayer != null) {
                            val player = mediaPlayer!!
                            val dur = player.duration.takeIf { it > 0 } ?: 0
                            val pos = player.currentPosition
                            val ratio = if (dur > 0) pos.toFloat() / dur.toFloat() else 0f
                            container.background = resources.getDrawable(R.drawable.bg_playing_border, context.theme)
                            view.post {
                                val total = view.width
                                val width = (total * ratio).toInt().coerceIn(0, total)
                                val lp = progress.layoutParams
                                lp.width = width
                                progress.layoutParams = lp
                            }
                        } else {
                            container.background = null
                            view.post {
                                val lp = progress.layoutParams
                                lp.width = 0
                                progress.layoutParams = lp
                            }
                        }
                    }
                    asTree != null && asTree.isDirectory -> {
                        val count = folderCounts[uri]
                        countView.text = count?.toString() ?: "…"
                        container.background = null
                        view.post {
                            val lp = progress.layoutParams
                            lp.width = 0
                            progress.layoutParams = lp
                        }
                    }
                    else -> countView.text = "…"
                }
            } else {
                countView.text = "…"
            }

            // 좌우 드래그로 재생 위치 탐색 (재생 중 해당 파일에만)
            view.setOnTouchListener { v, event ->
                if (selectionMode) return@setOnTouchListener false
                val rowUri = getCurrentFolderList().getOrNull(position)
                if (rowUri == null || rowUri != currentlyPlayingUri || mediaPlayer == null) return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val player = mediaPlayer ?: return@setOnTouchListener false
                        view.setTag(R.id.itemRoot, event.x) // 시작 X 위치
                        view.setTag(R.id.textCount, player.currentPosition) // 시작 재생 위치
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val startX = (view.getTag(R.id.itemRoot) as? Float) ?: return@setOnTouchListener false
                        val startPos = (view.getTag(R.id.textCount) as? Int) ?: return@setOnTouchListener false
                        val player = mediaPlayer ?: return@setOnTouchListener false
                        val duration = player.duration
                        if (duration <= 0) return@setOnTouchListener false
                        
                        val viewWidth = view.width.toFloat().coerceAtLeast(1f)
                        val dx = event.x - startX
                        val seekRatio = (dx / viewWidth).coerceIn(-0.5f, 0.5f) // 최대 ±50%
                        val maxSeekRange = duration / 2
                        val seekOffset = (seekRatio * maxSeekRange).toInt()
                        val newPos = (startPos + seekOffset).coerceIn(0, duration)
                        
                        player.seekTo(newPos)
                        folderAdapter.notifyDataSetChanged()
                        return@setOnTouchListener true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        false
                    }
                    else -> false
                }
            }

            return view
        }
    }

    private fun handleItemClickPlayback(uri: Uri) {
        // 파일만 재생. 폴더(tree) URI는 무시
        val single = androidx.documentfile.provider.DocumentFile.fromSingleUri(this, uri)
        if (single == null || !single.isFile) return

        if (currentlyPlayingUri == uri) {
            val mp = mediaPlayer
            if (mp != null) {
                if (mp.isPlaying) mp.pause() else mp.start()
                return
            }
        }

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setDataSource(this@MainActivity, uri)
            setOnPreparedListener { it.start() }
            setOnCompletionListener { /* 재생 종료 후 특별한 동작 없음 */ }
            prepareAsync()
        }
        currentlyPlayingUri = uri
        uiHandler.removeCallbacks(progressUpdater)
        uiHandler.post(progressUpdater)
    }

    override fun onStop() {
        super.onStop()
        mediaPlayer?.release()
        mediaPlayer = null
        currentlyPlayingUri = null
        uiHandler.removeCallbacks(progressUpdater)
    }

    override fun onDestroy() {
        super.onDestroy()
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
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                startActivity(intent)
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
                        val removed = currentList.removeAt(pos)
                        if (removed == currentlyPlayingUri) {
                            mediaPlayer?.release()
                            mediaPlayer = null
                            currentlyPlayingUri = null
                        }
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
                // 재생 중인 리소스 정리
                mediaPlayer?.release()
                mediaPlayer = null
                currentlyPlayingUri = null
                currentList.clear()
                deleteSelectedPositions.clear()
                updateDisplayList()
                persistFolders()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshCountsForCurrentCategory() {
        val list = getCurrentFolderList().toList()
        Thread {
            var updated = false
            for (uri in list) {
                val asTree = DocumentFile.fromTreeUri(this, uri)
                val asSingle = DocumentFile.fromSingleUri(this, uri)
                if (asTree != null && asTree.isDirectory) {
                    if (!folderCounts.containsKey(uri)) {
                        val count = countAudioRecursively(uri)
                        folderCounts[uri] = count
                        updated = true
                    }
                } else if (asSingle != null && asSingle.isFile) {
                    if (!fileDurationsMs.containsKey(uri)) {
                        val duration = readAudioDurationMs(uri)
                        if (duration != null) {
                            fileDurationsMs[uri] = duration
                            updated = true
                        }
                    }
                }
            }
            if (updated) runOnUiThread { folderAdapter.notifyDataSetChanged() }
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
            val mime = doc.type
            if (mime != null && mime.startsWith("audio")) return 1
            val name = doc.name?.lowercase() ?: return 0
            return if (name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".m4a") ||
                name.endsWith(".aac") || name.endsWith(".ogg") || name.endsWith(".flac")) 1 else 0
        }
        if (doc.isDirectory) {
            var total = 0
            val children = doc.listFiles()
            for (child in children) {
                total += countAudioInDocument(child)
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
        } catch (_: Exception) {
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
}