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
        // 모든 알림에 대해 즉시 소리 변경 (알림이 재생되기 전에)
        // 알림이 올 때마다 즉시 소리를 변경하여 바꾼 알림음이 재생되도록 함
        // Thread를 사용하지 않고 동기적으로 처리하여 더 빠르게 변경
        updateNotificationRingtone()
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
            
            // 알림 소리 변경 - 모든 알림 타입에 대해 처리
            // 알림이 재생되기 전에 소리를 변경하기 위해 최대한 빠르게 처리
            // 1. 기본 알림 소리 변경 (가장 먼저)
            val result = Settings.System.putString(contentResolver, Settings.System.NOTIFICATION_SOUND, audioUri.toString())
            
            // 2. SMS 알림 소리도 변경 시도 (문자 메시지 알림음 적용)
            try {
                // SMS 알림 소리도 변경 시도 (모든 Android 버전에서 시도)
                Settings.System.putString(contentResolver, "sms_notification_sound", audioUri.toString())
            } catch (e: Exception) {
                // SMS 알림 소리 변경 실패 시 무시
                android.util.Log.w("NotificationListener", "Failed to update SMS notification sound", e)
            }
            
            // 3. 추가 알림 소리 설정도 변경 시도 (다양한 알림 타입 지원)
            try {
                // 문자 메시지 알림 소리 (다른 키 시도)
                Settings.System.putString(contentResolver, "sms_delivered_sound", audioUri.toString())
            } catch (e: Exception) {
                // 무시
            }
            
            try {
                // 문자 메시지 알림 소리 (또 다른 키 시도)
                Settings.System.putString(contentResolver, "sms_received_sound", audioUri.toString())
            } catch (e: Exception) {
                // 무시
            }
            
            // 4. 알림 채널을 통한 소리 변경 시도 (Android 8.0+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                try {
                    // 알림 채널을 통해 소리 변경 시도
                    val notificationManager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    val channels = notificationManager.notificationChannels
                    val soundUri = android.net.Uri.parse(audioUri.toString())
                    val audioAttributes = android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                    
                    for (channel in channels) {
                        // SMS 관련 채널 찾기
                        if (channel.id.contains("sms", ignoreCase = true) || 
                            channel.id.contains("message", ignoreCase = true) ||
                            channel.id.contains("text", ignoreCase = true) ||
                            channel.id.contains("mms", ignoreCase = true)) {
                            try {
                                // 기존 채널 삭제 후 다시 생성 (소리 변경을 위해)
                                notificationManager.deleteNotificationChannel(channel.id)
                                val newChannel = android.app.NotificationChannel(
                                    channel.id,
                                    channel.name,
                                    channel.importance
                                )
                                newChannel.setSound(soundUri, audioAttributes)
                                newChannel.enableLights(channel.shouldShowLights())
                                newChannel.enableVibration(channel.shouldVibrate())
                                newChannel.vibrationPattern = channel.vibrationPattern
                                newChannel.lightColor = channel.lightColor
                                notificationManager.createNotificationChannel(newChannel)
                            } catch (e: Exception) {
                                // 채널 수정 실패 시 무시
                                android.util.Log.w("NotificationListener", "Failed to update channel: ${channel.id}", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 알림 채널 변경 실패 시 무시
                    android.util.Log.w("NotificationListener", "Failed to update notification channel sound", e)
                }
            }
            
            if (result) {
                android.util.Log.d("NotificationListener", "Notification sound updated: $audioUri")
            } else {
                android.util.Log.w("NotificationListener", "Failed to update notification sound")
            }
            
            // MainActivity에 알림 업데이트 요청 (Broadcast 사용)
            val intent = Intent("com.example.randomringtone.UPDATE_SOUNDS_NOTIFICATION")
            sendBroadcast(intent)
        } catch (e: Exception) {
            android.util.Log.e("NotificationListener", "Error updating notification sound", e)
            e.printStackTrace()
        }
    }
}

