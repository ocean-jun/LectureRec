package com.dsh.lecturerec

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dsh.lecturerec.databinding.ActivitySettingsBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var settings: AppSettings

    /** 程序化设置 Spinner 选中项时会触发回调，用它挡住，避免覆盖用户已填的内容。 */
    private var suppressPresetCallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        settings = AppSettings(this)

        b.spinnerPreset.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            Presets.ASR.map { it.label }
        )
        b.spinnerPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressPresetCallback) return
                val p = Presets.ASR[position]
                if (p.baseUrl.isNotBlank()) {
                    b.etBaseUrl.setText(p.baseUrl)
                    b.etModel.setText(p.model)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        b.spinnerVad.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            DetectorFactory.LABELS
        )

        b.spinnerSensitivity.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            VadSensitivity.LABELS
        )

        b.spinnerConcurrency.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            UploadTuning.CONCURRENCY_LABELS
        )

        b.spinnerTimestamp.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            Timestamps.LABELS
        )

        load()
        showBuildInfo()
        b.btnSave.setOnClickListener { save() }
    }

    /**
     * 显示版本号与安装时间。
     *
     * 之前版本号一直没升过，手机上永远显示同一个数字，根本分不清跑的是哪次构建 ——
     * 装上这个之后，「版本 1.5 (3) · 安装于 09-21 22:30」一眼就能对上是哪一份 APK。
     */
    @Suppress("DEPRECATION")
    private fun showBuildInfo() {
        val info = packageManager.getPackageInfo(packageName, 0)
        val installed = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
            .format(Date(info.lastUpdateTime))
        b.versionText.text = "版本 ${info.versionName} (${info.versionCode}) · 安装于 $installed"
    }

    private fun load() {
        suppressPresetCallback = true
        val idx = Presets.ASR.indexOfFirst {
            it.baseUrl == settings.asrBaseUrl && it.model == settings.asrModel
        }
        b.spinnerPreset.setSelection(if (idx >= 0) idx else Presets.ASR.lastIndex)
        suppressPresetCallback = false

        b.etBaseUrl.setText(settings.asrBaseUrl)
        b.etApiKey.setText(settings.asrApiKey)
        b.etModel.setText(settings.asrModel)
        b.etLanguage.setText(settings.language)
        b.etHotwords.setText(settings.hotwords)
        b.etTitle.setText(settings.courseTitle)
        b.spinnerVad.setSelection(DetectorFactory.indexOf(settings.vadEngine))
        b.spinnerSensitivity.setSelection(VadSensitivity.indexOf(settings.sensitivity))
        b.spinnerConcurrency.setSelection(
            UploadTuning.concurrencyIndexOf(settings.uploadConcurrency)
        )
        b.spinnerTimestamp.setSelection(Timestamps.indexOf(settings.timestampMode))

        b.switchPolish.isChecked = settings.polishEnabled
        b.etPolishBase.setText(settings.polishBaseUrl)
        b.etPolishKey.setText(settings.polishApiKey)
        b.etPolishModel.setText(settings.polishModel)
    }

    private fun save() {
        settings.asrBaseUrl = b.etBaseUrl.text?.toString()?.trim().orEmpty()
        settings.asrApiKey = b.etApiKey.text?.toString()?.trim().orEmpty()
        settings.asrModel = b.etModel.text?.toString()?.trim().orEmpty()
        settings.language = b.etLanguage.text?.toString()?.trim().orEmpty()
        settings.hotwords = b.etHotwords.text?.toString().orEmpty()
        settings.courseTitle = b.etTitle.text?.toString()?.trim().orEmpty()
        settings.vadEngine = DetectorFactory.keyOf(b.spinnerVad.selectedItemPosition)
        settings.sensitivity = VadSensitivity.valueOf(b.spinnerSensitivity.selectedItemPosition)
        settings.uploadConcurrency =
            UploadTuning.CONCURRENCY_OPTIONS[b.spinnerConcurrency.selectedItemPosition]
        settings.timestampMode = Timestamps.keyOf(b.spinnerTimestamp.selectedItemPosition)

        settings.polishEnabled = b.switchPolish.isChecked
        settings.polishBaseUrl = b.etPolishBase.text?.toString()?.trim().orEmpty()
        settings.polishApiKey = b.etPolishKey.text?.toString()?.trim().orEmpty()
        settings.polishModel = b.etPolishModel.text?.toString()?.trim().orEmpty()

        val msg = if (settings.asrApiKey.isBlank()) R.string.settings_missing_key else R.string.saved
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
