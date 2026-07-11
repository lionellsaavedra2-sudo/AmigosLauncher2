package net.kdt.pojavlaunch.custom

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.nio.charset.StandardCharsets
import java.util.UUID
import net.kdt.pojavlaunch.R

/**
 * Pantalla de inicio de sesión "Cracked" u Offline con opción de configurar RAM.
 * Valida el nombre de usuario y genera su UUID de manera determinista offline.
 */
class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val etUsername = findViewById<EditText>(R.id.et_username)
        val etRam = findViewById<EditText>(R.id.et_ram)
        val btnLogin = findViewById<Button>(R.id.btn_login)

        btnLogin.setOnClickListener {
            val username = etUsername.text.toString().trim()
            val ramInput = etRam.text.toString().trim()

            if (username.isEmpty()) {
                Toast.makeText(this, "Por favor, ingresa un nombre de usuario", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Validar que el nombre de usuario de Minecraft no contenga caracteres inválidos
            if (!username.matches(Regex("^[a-zA-Z0-9_]{3,16}$"))) {
                Toast.makeText(this, "El usuario debe tener de 3 a 16 caracteres alfanuméricos o guión bajo (_)", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // Validar asignación de RAM
            val ramValue = ramInput.toIntOrNull() ?: 2
            if (ramValue < 1 || ramValue > 32) {
                Toast.makeText(this, "Asigna un valor de RAM válido entre 1 y 32 GB", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // Generar UUID fuera de línea compatible con el algoritmo de Minecraft
            val offlineUuid = generateOfflineUuid(username)

            // Redirigir a la pantalla de sincronización enviando la sesión de autenticación offline y RAM seleccionada
            val intent = Intent(this, SyncActivity::class.java).apply {
                putExtra("username", username)
                putExtra("uuid", offlineUuid)
                putExtra("ram_gb", ramValue)
            }
            startActivity(intent)
            finish()
        }
    }

    /**
     * Algoritmo de generación de UUID offline estándar de Minecraft Java.
     */
    private fun generateOfflineUuid(username: String): String {
        val source = "OfflinePlayer:$username"
        val bytes = source.toByteArray(StandardCharsets.UTF_8)
        val uuid = UUID.nameUUIDFromBytes(bytes)
        return uuid.toString()
    }
}
