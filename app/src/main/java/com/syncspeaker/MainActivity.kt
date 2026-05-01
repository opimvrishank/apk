package com.syncspeaker

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.syncspeaker.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnMaster.setOnClickListener {
            startActivity(Intent(this, MasterActivity::class.java))
        }
        b.btnSlave.setOnClickListener {
            startActivity(Intent(this, SlaveActivity::class.java))
        }
    }
}
