package com.example.randomringtone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // 재부팅 시 벨소리, 알람, 알림 소리 자동 적용
            val prefs = context.getSharedPreferences("folder_prefs", Context.MODE_PRIVATE)
            
            // 벨소리 업데이트
            updateRingtone(context, prefs)
            
            // 알람 소리 업데이트
            updateAlarmRingtone(context, prefs)
            
            // 알림 소리 업데이트
            updateNotificationRingtone(context, prefs)
        }
    }
    
    private fun updateRingtone(context: Context, prefs: SharedPreferences) {
        val selectedString = prefs.getString("persistent_selected_ringtone", null) ?: return
        try {
            val arr = JSONArray(selectedString)
            if (arr.length() == 0) return
            val uris = mutableListOf<Uri>()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, null) ?: continue
                uris.add(Uri.parse(s))
            }
            if (uris.isEmpty()) return
            
            val selectedUri = uris.random()
            val audioUri = getRandomAudioFileFromUri(context, selectedUri) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.System.canWrite(context)) {
                    return
                }
            }
            Settings.System.putString(context.contentResolver, Settings.System.RINGTONE, audioUri.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun updateAlarmRingtone(context: Context, prefs: SharedPreferences) {
        val selectedString = prefs.getString("persistent_selected_alarm", null) ?: return
        try {
            val arr = JSONArray(selectedString)
            if (arr.length() == 0) return
            val uris = mutableListOf<Uri>()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, null) ?: continue
                uris.add(Uri.parse(s))
            }
            if (uris.isEmpty()) return
            
            val selectedUri = uris.random()
            val audioUri = getRandomAudioFileFromUri(context, selectedUri) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.System.canWrite(context)) {
                    return
                }
            }
            Settings.System.putString(context.contentResolver, Settings.System.ALARM_ALERT, audioUri.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun updateNotificationRingtone(context: Context, prefs: SharedPreferences) {
        val selectedString = prefs.getString("persistent_selected_notification", null) ?: return
        try {
            val arr = JSONArray(selectedString)
            if (arr.length() == 0) return
            val uris = mutableListOf<Uri>()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, null) ?: continue
                uris.add(Uri.parse(s))
            }
            if (uris.isEmpty()) return
            
            val selectedUri = uris.random()
            val audioUri = getRandomAudioFileFromUri(context, selectedUri) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.System.canWrite(context)) {
                    return
                }
            }
            Settings.System.putString(context.contentResolver, Settings.System.NOTIFICATION_SOUND, audioUri.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun getRandomAudioFileFromUri(context: Context, uri: Uri): Uri? {
        val asTree = DocumentFile.fromTreeUri(context, uri)
        val asSingle = DocumentFile.fromSingleUri(context, uri)
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
}

