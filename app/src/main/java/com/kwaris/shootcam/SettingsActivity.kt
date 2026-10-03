package com.kwaris.shootcam

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.settings_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<Preference>("calibrate")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), CalibrationActivity::class.java))
                true
            }
            findPreference<Preference>("reset_dot")?.setOnPreferenceClickListener {
                Config.prefs(requireContext()).edit()
                    .putFloat(Config.K_DOT_X, 0.5f).putFloat(Config.K_DOT_Y, 0.5f).apply()
                Toast.makeText(requireContext(), "Point rouge recentré", Toast.LENGTH_SHORT).show()
                true
            }
            findPreference<Preference>("guide")?.setOnPreferenceClickListener {
                Config.prefs(requireContext()).edit().putBoolean(Config.K_ONBOARDED, false).apply()
                Toast.makeText(requireContext(), "Le guide s'affichera au retour sur l'écran principal", Toast.LENGTH_SHORT).show()
                true
            }
            findPreference<Preference>("version")?.summary =
                requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        }
    }
}
