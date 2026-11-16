package com.bixby.assistant
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.activity.ComponentActivity
class WelcomeActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val prefs=getSharedPreferences("bixby_setup",MODE_PRIVATE)
  if(prefs.getBoolean("welcome_complete",false)){openHome();return}
  setContentView(R.layout.activity_welcome)
  findViewById<Button>(R.id.getStartedButton).setOnClickListener {
   val phone=findViewById<EditText>(R.id.phoneNumberInput).text.toString().trim()
   prefs.edit().putBoolean("welcome_complete",true).putBoolean("greeting_pending",true)
    .putString("local_phone_number",phone).apply()
   openHome()
  }
 }
 private fun openHome(){startActivity(Intent(this,MainActivity::class.java));finish()}
}