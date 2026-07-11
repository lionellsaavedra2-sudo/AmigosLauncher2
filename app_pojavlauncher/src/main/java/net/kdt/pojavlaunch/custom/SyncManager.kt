package net.kdt.pojavlaunch.custom

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Interfaz de callback para reportar el estado y progreso de la sincronización a la UI.
 */
interface SyncListener {
    fun onStatusChanged(status: String)
    fun onProgressUpdate(progress: Int, max: Int)
    fun onSyncComplete(metadata: InstanceMetadata)
    fun onSyncFailed(error: Throwable)
}

/**
 * Gestor principal de la Sincronización Delta y Aprovisionamiento del JRE.
 */
class SyncManager(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val REPO_A_URL = "https://raw.githubusercontent.com/lionellsaavedra2-sudo/AmigosLauncher/main/instances.yml"
        private const val REPO_B_BASE_URL = "https://raw.githubusercontent.com/lionellsaavedra2-sudo/AmigosLauncherContent/main/"
        
        // Plantilla para la descarga del JRE ARM64. Ajustar según el mirror de PojavLauncher o servidor propio.
        private const val JRE_DOWNLOAD_URL_TEMPLATE = "https://github.com/PojavLauncherTeam/openjdk-multiarch-jdk/releases/download/v%s-u%s/jre%s-openjdk-android-arm64.zip"
        
        private const val PREFS_NAME = "custom_launcher_prefs"
        private const val KEY_LOCAL_VERSION_PREFIX = "local_version_"
    }

    /**
     * Inicia el proceso de sincronización asíncrona para una instancia específica.
     */
    fun syncInstance(instanceId: String, listener: SyncListener) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                notifyStatus(listener, "Iniciando sincronización...")

                // 1. Obtener índice maestro de instancias (Repositorio A)
                notifyStatus(listener, "Descargando índice de instancias...")
                val masterIndex = fetchMasterIndex()
                val targetInstance = masterIndex.instances.find { it.id == instanceId }
                    ?: throw IllegalArgumentException("La instancia '$instanceId' no existe en el repositorio remoto.")

                // 2. Comprobar control de versiones local vs remoto
                val localVersion = getLocalVersion(targetInstance.id)
                val remoteVersion = targetInstance.version

                notifyStatus(listener, "Versión local: $localVersion | Versión remota: $remoteVersion")

                // Carpeta base de la instancia en el almacenamiento de Android sandbox
                val instanceDir = File(context.getExternalFilesDir(null), "instances/${targetInstance.folderName}")
                if (!instanceDir.exists()) {
                    instanceDir.mkdirs()
                }

                // Si la versión remota es superior, realizamos la sincronización delta
                if (isNewerVersion(remoteVersion, localVersion)) {
                    notifyStatus(listener, "Nueva versión detectada. Sincronizando archivos delta...")
                    performDeltaSync(targetInstance, instanceDir, listener)
                    
                    // Actualizar versión almacenada localmente
                    saveLocalVersion(targetInstance.id, remoteVersion)
                } else {
                    notifyStatus(listener, "La instancia local está actualizada.")
                }

                // 3. Validar y aprovisionar el JRE correspondiente
                notifyStatus(listener, "Verificando entorno de ejecución Java (JRE ${targetInstance.javaVersion})...")
                val jrePath = provisionJre(targetInstance.javaVersion, listener)

                notifyStatus(listener, "Sincronización finalizada con éxito.")
                notifySuccess(listener, targetInstance)

            } catch (e: Exception) {
                notifyError(listener, e)
            }
        }
    }

    /**
     * Descarga y deserializa el índice maestro del Repositorio A.
     */
    private fun fetchMasterIndex(): MasterInstanceIndex {
        val request = Request.Builder().url(REPO_A_URL).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Error al consultar Repositorio A: $response")
            val bodyString = response.body?.string() ?: throw IOException("Cuerpo de respuesta vacío en Repositorio A")
            return gson.fromJson(bodyString, MasterInstanceIndex::class.java)
        }
    }

    /**
     * Sincroniza mods y resourcepacks eliminando obsoletos y descargando nuevos/faltantes.
     */
    private fun performDeltaSync(instance: InstanceMetadata, instanceDir: File, listener: SyncListener) {
        val inventoryUrl = "$REPO_B_BASE_URL${instance.folderName}/instance.json"
        
        notifyStatus(listener, "Descargando inventario de contenido de la instancia...")
        val request = Request.Builder().url(inventoryUrl).build()
        val inventory = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Error al descargar inventario desde Repositorio B: $response")
            val bodyString = response.body?.string() ?: throw IOException("Cuerpo de respuesta vacío en inventario B")
            gson.fromJson(bodyString, InstanceInventory::class.java)
        }

        // Definir carpetas locales
        val modsDir = File(instanceDir, "mods")
        val rpDir = File(instanceDir, "resourcepacks")

        if (!modsDir.exists()) modsDir.mkdirs()
        if (!rpDir.exists()) rpDir.mkdirs()

        // --- PASO D: ESCANEO Y PURGA DELTA ---
        notifyStatus(listener, "Escaneando almacenamiento local para depuración...")
        purgeFolder(modsDir, inventory.mods)
        purgeFolder(rpDir, inventory.resourcepacks)

        // --- PASO E: DESCARGA DE FALTANTES ---
        val totalDownloads = calculateMissingCount(modsDir, inventory.mods) + calculateMissingCount(rpDir, inventory.resourcepacks)
        var currentDownloadIndex = 0

        if (totalDownloads > 0) {
            notifyProgress(listener, 0, totalDownloads)
            
            // Descargar mods faltantes
            for (modName in inventory.mods) {
                val modFile = File(modsDir, modName)
                if (!modFile.exists()) {
                    val downloadUrl = "$REPO_B_BASE_URL${instance.folderName}/mods/$modName"
                    notifyStatus(listener, "Descargando mod (${currentDownloadIndex + 1}/$totalDownloads): $modName")
                    downloadFile(downloadUrl, modFile)
                    currentDownloadIndex++
                    notifyProgress(listener, currentDownloadIndex, totalDownloads)
                }
            }

            // Descargar resourcepacks faltantes
            for (rpName in inventory.resourcepacks) {
                val rpFile = File(rpDir, rpName)
                if (!rpFile.exists()) {
                    val downloadUrl = "$REPO_B_BASE_URL${instance.folderName}/resourcepacks/$rpName"
                    notifyStatus(listener, "Descargando textura (${currentDownloadIndex + 1}/$totalDownloads): $rpName")
                    downloadFile(downloadUrl, rpFile)
                    currentDownloadIndex++
                    notifyProgress(listener, currentDownloadIndex, totalDownloads)
                }
            }
        }
    }

    /**
     * Purga archivos locales que ya no existen en el inventario remoto de GitHub.
     */
    private fun purgeFolder(directory: File, remoteFiles: List<String>) {
        val localFiles = directory.listFiles() ?: return
        val remoteSet = remoteFiles.toSet()

        for (file in localFiles) {
            if (file.isFile && !remoteSet.contains(file.name)) {
                val deleted = file.delete()
                if (deleted) {
                    System.out.println("Delta Purge: Archivo obsoleto eliminado: ${file.name}")
                }
            }
        }
    }

    private fun calculateMissingCount(directory: File, remoteFiles: List<String>): Int {
        var count = 0
        for (name in remoteFiles) {
            if (!File(directory, name).exists()) {
                count++
            }
        }
        return count
    }

    /**
     * Descarga un archivo a disco reportando progreso.
     */
    private fun downloadFile(url: String, destination: File) {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Error al descargar archivo: $response")
            val body = response.body ?: throw IOException("Cuerpo de respuesta vacío durante descarga de $url")
            
            body.byteStream().use { inputStream ->
                FileOutputStream(destination).use { outputStream ->
                    val data = ByteArray(4096)
                    var count: Int
                    while (inputStream.read(data).also { count = it } != -1) {
                        outputStream.write(data, 0, count)
                    }
                    outputStream.flush()
                }
            }
        }
    }

    /**
     * PASO F: Provisionamiento Automatizado del JRE ARM64.
     */
    private fun provisionJre(javaVersion: String, listener: SyncListener): String {
        // Carpeta interna protegida y privada de la aplicación
        val jreDir = File(context.filesDir, "jres/jre$javaVersion")
        val javaBinary = File(jreDir, "bin/java")

        if (javaBinary.exists() && javaBinary.canExecute()) {
            return jreDir.absolutePath
        }

        notifyStatus(listener, "El JRE $javaVersion no existe o no es ejecutable. Descargando JRE ARM64...")
        
        // En base a la versión de Java requerida, definimos las tags/versiones de compilación (ej. Adoptium o Pojav API mirrors)
        val (tag, uVersion) = when (javaVersion) {
            "8" -> Pair("8", "312")
            "16" -> Pair("16", "36")
            "17" -> Pair("17.0.1", "1")
            "21" -> Pair("21", "0.1")
            else -> throw IllegalArgumentException("Versión de Java no soportada: $javaVersion. Soporta: 8, 16, 17, 21")
        }

        val jreUrl = String.format(JRE_DOWNLOAD_URL_TEMPLATE, tag, uVersion, javaVersion)
        val tempZip = File(context.cacheDir, "jre_${javaVersion}_temp.zip")

        try {
            // Descargar paquete JRE optimizado para Android
            downloadFile(jreUrl, tempZip)

            notifyStatus(listener, "Extrayendo JRE $javaVersion...")
            if (jreDir.exists()) jreDir.deleteRecursively()
            jreDir.mkdirs()

            extractZip(tempZip, jreDir)

            // Dar permisos POSIX de ejecución a toda la estructura binaria de la JVM
            notifyStatus(listener, "Configurando permisos de ejecución (chmod)...")
            setExecutePermissions(jreDir)

        } finally {
            if (tempZip.exists()) {
                tempZip.delete()
            }
        }

        if (!javaBinary.exists()) {
            throw IOException("Fallo la provisión de JRE: No se encontró el ejecutable binario en ${javaBinary.absolutePath}")
        }

        return jreDir.absolutePath
    }

    /**
     * Extrae un archivo ZIP.
     */
    private fun extractZip(zipFile: File, destDir: File) {
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val file = File(destDir, entry.name)
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { fos ->
                        val buffer = ByteArray(4096)
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            fos.write(buffer, 0, len)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * Aplica permisos POSIX chmod 755 de forma recursiva al directorio del JRE.
     */
    private fun setExecutePermissions(directory: File) {
        try {
            // Asigna permisos de lectura, escritura y ejecución recursivamente usando Runtime.exec
            val process = Runtime.getRuntime().exec(arrayOf("chmod", "-R", "755", directory.absolutePath))
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                // Fallback manual iterando archivos si chmod general falla en algunas configuraciones sandbox de Android
                setPermissionsFallback(directory)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            setPermissionsFallback(directory)
        }
    }

    private fun setPermissionsFallback(file: File) {
        if (file.isDirectory) {
            file.setExecutable(true, false)
            file.setReadable(true, false)
            file.listFiles()?.forEach { setPermissionsFallback(it) }
        } else {
            // Si es un binario ejecutable o biblioteca compartida .so, aplicamos permisos
            if (file.name == "java" || file.name == "keytool" || file.name.endsWith(".so") || file.parentFile?.name == "bin") {
                file.setExecutable(true, false)
                file.setReadable(true, false)
            }
        }
    }

    // --- MANEJO DE CONTROL DE VERSIONES LOCAL ---

    private fun getLocalVersion(instanceId: String): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LOCAL_VERSION_PREFIX + instanceId, "0.0.0") ?: "0.0.0"
    }

    private fun saveLocalVersion(instanceId: String, version: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LOCAL_VERSION_PREFIX + instanceId, version).apply()
    }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        // Comparación simple de versiones string (ej. "1.1.0" vs "1.0.0")
        val remoteParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val localParts = local.split(".").mapNotNull { it.toIntOrNull() }

        val length = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until length) {
            val rVal = if (i < remoteParts.size) remoteParts[i] else 0
            val lVal = if (i < localParts.size) localParts[i] else 0
            if (rVal > lVal) return true
            if (rVal < lVal) return false
        }
        return false
    }

    // --- COMUNICACIÓN CON EL HILO PRINCIPAL (MAIN THREAD UI) ---

    private fun notifyStatus(listener: SyncListener, status: String) {
        mainHandler.post { listener.onStatusChanged(status) }
    }

    private fun notifyProgress(listener: SyncListener, progress: Int, max: Int) {
        mainHandler.post { listener.onProgressUpdate(progress, max) }
    }

    private fun notifySuccess(listener: SyncListener, metadata: InstanceMetadata) {
        mainHandler.post { listener.onSyncComplete(metadata) }
    }

    private fun notifyError(listener: SyncListener, error: Throwable) {
        mainHandler.post { listener.onSyncFailed(error) }
    }
}
