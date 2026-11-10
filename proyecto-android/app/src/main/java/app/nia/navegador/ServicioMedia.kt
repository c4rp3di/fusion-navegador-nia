package app.nia.navegador

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder

/**
 * T065 v2 (R0385, RES-0154): servicio multimedia foreground del
 * shell con MediaSession y controles del sistema (play/pausa/stop y
 * seek desde la notificacion o el lockscreen). Mismo proceso que
 * BrowserActivity: las ordenes llegan por el manejador del
 * companion SIN IPC. SIN servicio residente sin reproduccion
 * (R0137): el shell arranca el servicio al abrir el player y lo
 * para al cerrarlo; si el shell muere, la primera orden huerfana
 * apaga el servicio solo (nada queda colgado).
 *
 * Framework puro, sin androidx ni libs externas; icono del sistema
 * en la notificacion (el APK no trae recursos propios).
 */
class ServicioMedia : Service() {

    private var sesion: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instancia = this
        crearCanal()
        sesion = MediaSession(this, "nia-media").also { s ->
            s.setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    despachar("play", 0L)
                }

                override fun onPause() {
                    despachar("pause", 0L)
                }

                override fun onStop() {
                    despachar("stop", 0L)
                }

                override fun onSeekTo(pos: Long) {
                    despachar("seek", pos)
                }
            })
            s.isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, idArranque: Int): Int {
        when (intent?.action) {
            ACCION_PLAY -> despachar("play", 0L)
            ACCION_PAUSE -> despachar("pause", 0L)
            ACCION_STOP -> despachar("stop", 0L)
        }
        val notificacion = construirNotificacion()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                ID_NOTIFICACION, notificacion,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(ID_NOTIFICACION, notificacion)
        }
        actualizarSesion()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        sesion?.isActive = false
        sesion?.release()
        sesion = null
        instancia = null
        super.onDestroy()
    }

    /** Orden al shell; sin shell vivo el servicio es huerfano y se
     *  apaga SOLO (R0137: nada residente sin reproduccion). */
    private fun despachar(orden: String, ms: Long) {
        val m = manejador
        if (m == null) {
            stopSelf()
            return
        }
        m(orden, ms)
        actualizarSesion()
    }

    /** Estado del MediaSession para el sistema. */
    private fun actualizarSesion() {
        val s = sesion ?: return
        val est = if (reproduciendo) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        try {
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
                            PlaybackState.ACTION_SEEK_TO
                    )
                    .setState(est, posicionMs, if (reproduciendo) 1f else 0f)
                    .build()
            )
        } catch (liberada: IllegalStateException) {
            // La sesion murio con el cierre: el sistema ya no la ve.
        }
    }

    /** Notificacion con la accion opuesta al estado y Parar SIEMPRE. */
    private fun construirNotificacion(): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0,
            Intent(this, BrowserActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("NIA player")
            .setContentText(if (reproduciendo) "Reproduciendo" else "En pausa")
            .setContentIntent(abrir)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (reproduciendo) {
            builder.addAction(
                Notification.Action.Builder(null, "Pausa", pendiente(ACCION_PAUSE, 2)).build()
            )
        } else {
            builder.addAction(
                Notification.Action.Builder(null, "Reproducir", pendiente(ACCION_PLAY, 1)).build()
            )
        }
        builder.addAction(Notification.Action.Builder(null, "Parar", pendiente(ACCION_STOP, 3)).build())
        builder.setStyle(Notification.MediaStyle().setMediaSession(sesion?.sessionToken))
        return builder.build()
    }

    private fun pendiente(accion: String, codigo: Int): PendingIntent =
        PendingIntent.getService(
            this, codigo,
            Intent(this, ServicioMedia::class.java).setAction(accion),
            PendingIntent.FLAG_IMMUTABLE
        )

    private fun crearCanal() {
        val administrador = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        administrador.createNotificationChannel(
            NotificationChannel(CANAL, "Media", NotificationManager.IMPORTANCE_LOW)
        )
    }

    /** Repinta el estado del sistema; la notificacion SOLO cuando el
     *  estado visible cambia (sin churn por tick). */
    private fun pintar(cambioVisible: Boolean) {
        actualizarSesion()
        if (!cambioVisible) return
        val administrador = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        administrador.notify(ID_NOTIFICACION, construirNotificacion())
    }

    companion object {
        const val ACCION_PLAY = "nia.media.PLAY"
        const val ACCION_PAUSE = "nia.media.PAUSE"
        const val ACCION_STOP = "nia.media.STOP"
        private const val CANAL = "nia-media"
        private const val ID_NOTIFICACION = 46

        /** Ordenes que el shell atiende (mismo proceso):
         *  play / pause / stop / seek. */
        var manejador: ((String, Long) -> Unit)? = null

        private var instancia: ServicioMedia? = null
        private var reproduciendo = false
        private var posicionMs = 0L
        private var duracionMs = 0L

        /** Estado del player que el shell informa desde su refresco;
         *  el repintado visible SOLO cuando cambia el estado. */
        fun estado(reproduciendoAhora: Boolean, posicion: Long, duracion: Long) {
            val cambio = reproduciendoAhora != reproduciendo
            reproduciendo = reproduciendoAhora
            posicionMs = posicion
            duracionMs = duracion
            instancia?.pintar(cambio)
        }
    }
}
