package net.kdt.pojavlaunch.custom

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import net.kdt.pojavlaunch.R

/**
 * Pantalla que gestiona visualmente el progreso de la sincronización delta,
 * procesa la memoria RAM asignada e inicia el juego al terminar.
 */
class SyncActivity : AppCompatActivity() {

    private lateinit var tvInstanceName: TextView
    private lateinit var tvSyncStatus: TextView
    private lateinit var tvProgressDetail: TextView
    private lateinit var pbSyncProgress: ProgressBar
    private lateinit var pbLoading: ProgressBar

    private lateinit var syncManager: SyncManager
    private lateinit var launchConfigurator: LaunchConfigurator
    private var ramGb: Int = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sync)

        // Inicializar vistas
        tvInstanceName = findViewById(R.id.tv_instance_name)
        tvSyncStatus = findViewById(R.id.tv_sync_status)
        tvProgressDetail = findViewById(R.id.tv_progress_detail)
        pbSyncProgress = findViewById(R.id.pb_sync_progress)
        pbLoading = findViewById(R.id.pb_loading)

        // Obtener datos del intent (usuario, uuid y memoria RAM seleccionada)
        val username = intent.getStringExtra("username") ?: "Player"
        val uuid = intent.getStringExtra("uuid") ?: ""
        ramGb = intent.getIntExtra("ram_gb", 2)

        val authSession = AuthSession(
            username = username,
            uuid = uuid,
            accessToken = "cracked_token_active_session"
        )

        // Instanciar managers
        syncManager = SyncManager(applicationContext)
        launchConfigurator = LaunchConfigurator(applicationContext)

        // ID de la instancia por defecto a cargar (según el instances.yml del usuario)
        val defaultInstanceId = "survival_amigos_1201"

        // Iniciar ciclo de sincronización asíncrona
        startSyncProcess(defaultInstanceId, authSession)
    }

    private fun startSyncProcess(instanceId: String, authSession: AuthSession) {
        syncManager.syncInstance(instanceId, object : SyncListener {
            override fun onStatusChanged(status: String) {
                tvSyncStatus.text = status
            }

            override fun onProgressUpdate(progress: Int, max: Int) {
                pbLoading.visibility = View.GONE
                pbSyncProgress.visibility = View.VISIBLE
                tvProgressDetail.visibility = View.VISIBLE
                
                pbSyncProgress.max = max
                pbSyncProgress.progress = progress
                tvProgressDetail.text = "$progress / $max archivos procesados"
            }

            override fun onSyncComplete(metadata: InstanceMetadata) {
                tvInstanceName.text = metadata.name
                tvSyncStatus.text = "Iniciando Minecraft ${metadata.minecraftVersion}..."
                
                // Preparar los argumentos de ejecución inyectando la RAM configurada por el usuario
                try {
                    val customJvmArgs = listOf(
                        "-Xmx${ramGb}G",
                        "-Xms512M",
                        "-XX:+UnlockExperimentalVMOptions",
                        "-XX:+UseG1GC",
                        "-XX:G1NewSizePercent=20",
                        "-XX:G1ReservePercent=20",
                        "-XX:MaxGCPauseMillis=50",
                        "-XX:G1HeapRegionSize=32m"
                    )

                    val command = launchConfigurator.prepareLaunch(
                        metadata = metadata,
                        authSession = authSession,
                        customJavaArgs = customJvmArgs
                    )
                    
                    // Iniciar PojavLauncher GameActivity nativo con nuestro comando inyectado
                    launchGame(command)
                } catch (e: Exception) {
                    showErrorDialog("Error al preparar la ejecución: ${e.localizedMessage}")
                }
            }

            override fun onSyncFailed(error: Throwable) {
                showErrorDialog(error.localizedMessage ?: "Ocurrió un error inesperado durante la descarga.")
            }
        })
    }

    /**
     * Pasa el control de ejecución al motor de PojavLauncher (GameActivity).
     */
    private fun launchGame(command: LaunchConfigurator.LaunchCommand) {
        val gameIntent = Intent().apply {
            setClassName(packageName, "net.kdt.pojavlaunch.GameActivity")
            putExtra("java_path", command.binaryPath)
            putStringArrayListExtra("java_args", ArrayList(command.arguments))
            
            val envBundle = Bundle()
            command.environmentVariables.forEach { (k, v) -> envBundle.putString(k, v) }
            putExtra("env_variables", envBundle)
            
            putExtra("working_dir", command.workingDirectory.absolutePath)
        }
        
        startActivity(gameIntent)
        finish()
    }

    /**
     * Muestra ventana emergente de error y regresa a la pantalla de login.
     */
    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Fallo de Launcher")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Regresar") { _, _ ->
                val intent = Intent(this, LoginActivity::class.java)
                startActivity(intent)
                finish()
            }
            .show()
    }
}
