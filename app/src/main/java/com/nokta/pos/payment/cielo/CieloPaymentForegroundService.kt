package com.nokta.pos.payment.cielo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.nokta.pos.R

/**
 * Exigido pela Cielo Smart (manual oficial, "Serviço em primeiro plano"):
 * mantém o processo do app parceiro vivo enquanto o app Cielo está em
 * primeiro plano processando o pagamento — evita o Android encerrar o
 * processo Nokta POS por estar em segundo plano durante a cobrança.
 *
 * Iniciado logo antes de disparar o Intent de pagamento
 * ([CieloDeepLinkPaymentProvider.startPayment]) e parado assim que o
 * resultado chega ([CieloPaymentResponseActivity]) — ou quando a tentativa
 * termina sem callback nenhum (timeout/app da Cielo ausente), para nunca
 * deixar uma notificação viva sem cobrança acontecendo.
 *
 * Usar sempre [start]/[stop], nunca `startService` solto: `startForeground`
 * precisa acontecer dentro da janela que o Android concede após o start, e
 * `START_NOT_STICKY` garante que o serviço não é ressuscitado sozinho depois
 * de o processo morrer (o que reviveria a notificação sem pagamento algum).
 */
class CieloPaymentForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.cielo_payment_in_progress_title))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.cielo_payment_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "cielo_payment_channel"
        const val NOTIFICATION_ID = 1001

        /**
         * Falha ao iniciar nunca pode derrubar a cobrança: o pagamento em si
         * funciona sem o serviço (ele só reduz a chance de o processo ser
         * morto), então um erro de plataforma aqui é engolido de propósito —
         * o pior caso volta a ser exatamente o comportamento anterior a esta
         * proteção existir.
         */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, CieloPaymentForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CieloPaymentForegroundService::class.java)) }
        }
    }
}
