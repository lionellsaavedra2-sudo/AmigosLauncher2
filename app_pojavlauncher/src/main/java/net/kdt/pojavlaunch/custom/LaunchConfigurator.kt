package net.kdt.pojavlaunch.custom

import android.content.Context
import java.io.File

/**
 * Clase encargada de estructurar las variables de entorno y construir los parámetros
 * necesarios para ejecutar Minecraft Java mediante el JRE y las bibliotecas nativas (GL4ES, Mesa, etc.).
 */
class LaunchConfigurator(private val context: Context) {

    /**
     * Contenedor de datos que agrupa la configuración lista para pasar a ProcessBuilder.
     */
    data class LaunchCommand(
        val binaryPath: String,
        val arguments: List<String>,
        val environmentVariables: Map<String, String>,
        val workingDirectory: File
    )

    /**
     * Construye la configuración de ejecución completa para la instancia de Minecraft.
     *
     * @param metadata Metadatos de la instancia sincronizada.
     * @param authSession Sesión de autenticación del usuario (UUID, Token, etc.).
     * @param customJavaArgs Argumentos de Java opcionales adicionales (ej. "-Xmx2G", "-Xms512M").
     * @return Objeto LaunchCommand estructurado con argumentos y variables de entorno.
     */
    fun prepareLaunch(
        metadata: InstanceMetadata,
        authSession: AuthSession,
        customJavaArgs: List<String> = listOf("-Xmx2G")
    ): LaunchCommand {
        
        // 1. Resolver el JRE e identificar el binario de ejecución
        val jreDir = File(context.filesDir, "jres/jre${metadata.javaVersion}")
        val javaBinary = File(jreDir, "bin/java")
        if (!javaBinary.exists()) {
            throw IllegalStateException("El binario de Java no se encuentra en ${javaBinary.absolutePath}. Ejecuta la sincronización primero.")
        }

        // 2. Determinar directorios de juego locales
        val instanceDir = File(context.getExternalFilesDir(null), "instances/${metadata.folderName}")
        val assetsDir = File(context.getExternalFilesDir(null), "assets") // Ubicación global de assets de Minecraft
        
        // 3. Directorio de librerías nativas (.so) del launcher (GL4ES, OpenAL, GLFW stub)
        // Android desempaqueta las librerías nativas de la APK en context.applicationInfo.nativeLibraryDir
        val nativeLibDir = context.applicationInfo.nativeLibraryDir

        // 4. Configurar variables de entorno para traducir OpenGL a OpenGL ES / Vulkan (Mesa 3D, VirGL, GL4ES)
        val env = mutableMapOf<String, String>()
        
        // Direccionar rutas de librerías nativas para simular un linker de Linux
        env["LD_LIBRARY_PATH"] = nativeLibDir
        env["PATH"] = "${jreDir.absolutePath}/bin:/system/bin:/system/xbin"
        
        // Configuración de visualización virtual sobre GLFW y wrapper de OpenGL
        env["POJAV_NATIVE_LIB_DIR"] = nativeLibDir
        
        // Overrides gráficos para Mesa 3D / VirGL / GL4ES
        // Mapear OpenGL de escritorio a una versión virtual que Minecraft entienda
        env["MESA_GL_VERSION_OVERRIDE"] = "4.5"
        env["MESA_GLSL_VERSION_OVERRIDE"] = "450"
        
        // Optimización de sincronización vertical y refresco
        env["vblank_mode"] = "0"
        
        // Configuración específica de PojavLauncher para VirGL (si se usa Mesa VirGL renderer)
        env["VIRGL_NO_SURFACELESS"] = "1"

        // 5. Construir los argumentos de la JVM (Java Virtual Machine)
        val jvmArgs = mutableListOf<String>()
        
        // Configurar el cargador de librerías nativas seguro para Android JNI
        jvmArgs.add("-Djava.library.path=$nativeLibDir")
        
        // Configuración de propiedades del sistema PojavLauncher
        jvmArgs.add("-Dpojav.gameDirectory=${instanceDir.absolutePath}")
        jvmArgs.add("-Dpojav.assetsDirectory=${assetsDir.absolutePath}")
        
        // Añadir argumentos JVM customizados de memoria y optimización GC
        jvmArgs.addAll(customJavaArgs)
        
        // Agregar compatibilidad de clase y parches de carga para Android (ej. logs, codecs, etc.)
        jvmArgs.add("-Djava.awt.headless=true")
        jvmArgs.add("-Dorg.lwjgl.librarypath=$nativeLibDir")

        // 6. Configurar la Main Class según el loader (Fabric, Forge o Vanilla)
        val mainClass = when (metadata.loader.lowercase()) {
            "fabric" -> "net.fabricmc.loader.impl.launch.knot.KnotClient"
            "forge" -> "net.minecraft.launchwrapper.Launch" // Para versiones antiguas de Forge, ajustarse para modlauncher si es moderno
            else -> "net.minecraft.client.main.Main" // Vanilla
        }

        // 7. Construir los argumentos de lanzamiento de Minecraft
        val mcArgs = mutableListOf<String>()
        
        mcArgs.add("--username")
        mcArgs.add(authSession.username)
        
        mcArgs.add("--version")
        mcArgs.add(metadata.minecraftVersion)
        
        mcArgs.add("--gameDir")
        mcArgs.add(instanceDir.absolutePath)
        
        mcArgs.add("--assetsDir")
        mcArgs.add(assetsDir.absolutePath)
        
        mcArgs.add("--assetIndex")
        mcArgs.add(getAssetIndexName(metadata.minecraftVersion))
        
        mcArgs.add("--uuid")
        mcArgs.add(authSession.uuid)
        
        mcArgs.add("--accessToken")
        mcArgs.add(authSession.accessToken)
        
        mcArgs.add("--userType")
        mcArgs.add(authSession.userType)
        
        mcArgs.add("--versionType")
        mcArgs.add("release")

        // Si se trata de Fabric/Forge, inyectamos los argumentos específicos
        if (metadata.loader.lowercase() == "fabric") {
            // Fabric requiere especificar los directorios de classpath del cargador
            // Nota: En la ejecución real de PojavLauncher, los JARs de cargadores y bibliotecas de Minecraft
            // se le agregan al classpath (-cp / -classpath) mediante un constructor dinámico.
        }

        // 8. Integrar todo el comando de lanzamiento final
        val fullArguments = mutableListOf<String>()
        fullArguments.addAll(jvmArgs)
        fullArguments.add(mainClass)
        fullArguments.addAll(mcArgs)

        return LaunchCommand(
            binaryPath = javaBinary.absolutePath,
            arguments = fullArguments,
            environmentVariables = env,
            workingDirectory = instanceDir
        )
    }

    /**
     * Mapea la versión del juego con el nombre de su respectivo índice de assets.
     */
    private fun getAssetIndexName(mcVersion: String): String {
        return when {
            mcVersion.startsWith("1.20") -> "1.20"
            mcVersion.startsWith("1.19") -> "1.19"
            mcVersion.startsWith("1.18") -> "1.18"
            mcVersion.startsWith("1.17") -> "1.17"
            mcVersion.startsWith("1.16") -> "1.16"
            else -> "legacy"
        }
    }
}

/**
 * Representa la sesión de autenticación mapeada.
 */
data class AuthSession(
    val username: String,
    val uuid: String,
    val accessToken: String,
    val userType: String = "mojang"
)
