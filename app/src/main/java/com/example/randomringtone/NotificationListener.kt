package com.example.randomringtone

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray

class NotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        // 알림이 소리를 재생하는 경우에만 알림 소리 변경
        val notification = sbn.notification
        // 알림에 소리가 있거나, 기본 알림 소리를 사용하는 경우 변경
        if (notification.sound != null || notification.defaults and android.app.Notification.DEFAULT_SOUND != 0) {
            Thread {
                updateNotificationRingtone()
            }.start()
        }
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

    private fun updateNotificationRingtone() {
        val prefs = getSharedPreferences("folder_prefs", MODE_PRIVATE)
        val selectedString = prefs.getString("persistent_selected_notification", null) ?: return
        try {
            val arr = JSONArray(selectedString)
            if (arr.length() == 0) return
            val uris = mutableListOf<Uri>()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, null) ?: continue
                try {
                    uris.add(Uri.parse(s))
                } catch (e: Exception) {
                    // URI 파싱 실패 시 무시
                    continue
                }
            }
            if (uris.isEmpty()) return
            
            val selectedUri = uris.random()
            val audioUri = getRandomAudioFileFromUri(selectedUri) ?: return
            
            // WRITE_SETTINGS 권한 확인
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                if (!Settings.System.canWrite(this)) {
                    android.util.Log.w("NotificationListener", "WRITE_SETTINGS permission not granted")
                    return
                }
            }
            
            // 알림 소리 변경
            val result = Settings.System.putString(contentResolver, Settings.System.NOTIFICATION_SOUND, audioUri.toString())
            if (result) {
                android.util.Log.d("NotificationListener", "Notification sound updated: $audioUri")
            } else {
                android.util.Log.w("NotificationListener", "Failed to update notification sound")
            }
        } catch (e: Exception) {
            android.util.Log.e("NotificationListener", "Error updating notification sound", e)
            e.printStackTrace()
        }
    }
}

