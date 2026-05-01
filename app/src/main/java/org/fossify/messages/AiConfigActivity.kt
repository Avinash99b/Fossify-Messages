package org.fossify.messages

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.fossify.commons.extensions.viewBinding
import org.fossify.messages.databinding.ActivityAiConfigBinding
import org.fossify.messages.helpers.AiConfigProvider

class AiConfigActivity : AppCompatActivity() {

    val binding by viewBinding(ActivityAiConfigBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        configure()
    }

    private fun configure(){
        val aiConfigProvider = AiConfigProvider(this)
        binding.saveBtn.setOnClickListener {
            val apiKey = binding.apiKeyInput.text
            val modelName = binding.modelNameInput.text
            if(apiKey.isNullOrEmpty()){
                Toast.makeText(this, "Please enter valid api key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if(modelName.isNullOrEmpty()){
                Toast.makeText(this, "Please enter valid Model Name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            //Toast.makeText(this, "Checking api key", Toast.LENGTH_SHORT).show()
            aiConfigProvider.apiKey=apiKey.toString()
            aiConfigProvider.model=modelName.toString()


            Toast.makeText(this, "Updated Successfully", Toast.LENGTH_SHORT).show()
        }

        binding.clearDataBtn.setOnClickListener {
            aiConfigProvider.clear()
            Toast.makeText(this, "Cleared", Toast.LENGTH_SHORT).show()
        }
    }

}
