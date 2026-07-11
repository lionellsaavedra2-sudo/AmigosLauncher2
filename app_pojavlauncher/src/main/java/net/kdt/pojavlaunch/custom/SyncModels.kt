package net.kdt.pojavlaunch.custom

import com.google.gson.annotations.SerializedName

/**
 * Representa el índice maestro obtenido del Repositorio A.
 */
data class MasterInstanceIndex(
    @SerializedName("instances")
    val instances: List<InstanceMetadata>
)

/**
 * Representa los metadatos individuales de una instancia de Minecraft.
 */
data class InstanceMetadata(
    @SerializedName("id")
    val id: String,
    
    @SerializedName("name")
    val name: String,
    
    @SerializedName("version")
    val version: String, // Ejemplo: "1.1.0"
    
    @SerializedName("minecraft_version")
    val minecraftVersion: String, // Ejemplo: "1.20.1"
    
    @SerializedName("loader")
    val loader: String, // Ejemplo: "fabric", "forge", "vanilla"
    
    @SerializedName("loader_version")
    val loaderVersion: String, // Ejemplo: "0.15.11"
    
    @SerializedName("folder_name")
    val folderName: String, // Carpeta en la que se guardarán los datos (ej. "survival-mods")
    
    @SerializedName("java_version")
    val javaVersion: String // Ejemplo: "8", "16", "17", "21"
)

/**
 * Representa el inventario de contenido (mods y resourcepacks) obtenido del Repositorio B.
 */
data class InstanceInventory(
    @SerializedName("mods")
    val mods: List<String>,
    
    @SerializedName("resourcepacks")
    val resourcepacks: List<String>
)
