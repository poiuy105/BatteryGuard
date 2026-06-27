package com.batteryguard;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "BatteryGuardPrefs";

    private SeekBar sbTOn, sbTOff, sbHys;
    private TextView tvTOnValue, tvTOffValue, tvHysValue;
    private Button btnSave, btnUnbind, btnForceUnbind, btnBack;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        sbTOn = findViewById(R.id.sb_t_on);
        sbTOff = findViewById(R.id.sb_t_off);
        sbHys = findViewById(R.id.sb_hys);
        tvTOnValue = findViewById(R.id.tv_t_on_value);
        tvTOffValue = findViewById(R.id.tv_t_off_value);
        tvHysValue = findViewById(R.id.tv_hys_value);
        btnSave = findViewById(R.id.btn_save);
        btnUnbind = findViewById(R.id.btn_unbind);
        btnForceUnbind = findViewById(R.id.btn_force_unbind);
        btnBack = findViewById(R.id.btn_back);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int tOn = prefs.getInt("t_on", 30);
        int tOff = prefs.getInt("t_off", 90);
        int hys = prefs.getInt("hys", 5);

        sbTOn.setProgress(tOn - 5);
        sbTOff.setProgress(tOff - 10);
        sbHys.setProgress(hys - 1);

        updateLabels(tOn, tOff, hys);

        sbTOn.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int val = progress + 5;
                int tOffVal = sbTOff.getProgress() + 10;
                int hysVal = sbHys.getProgress() + 1;
                if (val >= tOffVal) {
                    sbTOff.setProgress(val - 5);
                    tOffVal = sbTOff.getProgress() + 10;
                }
                if (hysVal > val) {
                    sbHys.setProgress(val - 1);
                    hysVal = sbHys.getProgress() + 1;
                }
                updateLabels(val, tOffVal, hysVal);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        sbTOff.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int val = progress + 10;
                int tOnVal = sbTOn.getProgress() + 5;
                int hysVal = sbHys.getProgress() + 1;
                if (val <= tOnVal) {
                    sbTOn.setProgress(val - 6);
                    tOnVal = sbTOn.getProgress() + 5;
                }
                if (hysVal > (100 - val)) {
                    sbHys.setProgress(100 - val - 1);
                    hysVal = sbHys.getProgress() + 1;
                }
                updateLabels(tOnVal, val, hysVal);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        sbHys.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int val = progress + 1;
                int tOnVal = sbTOn.getProgress() + 5;
                int tOffVal = sbTOff.getProgress() + 10;
                if (val > tOnVal) {
                    sbHys.setProgress(tOnVal - 1);
                    val = sbHys.getProgress() + 1;
                }
                if (val > (100 - tOffVal)) {
                    sbHys.setProgress(100 - tOffVal - 1);
                    val = sbHys.getProgress() + 1;
                }
                updateLabels(tOnVal, tOffVal, val);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        btnSave.setOnClickListener(v -> {
            int tOnVal = sbTOn.getProgress() + 5;
            int tOffVal = sbTOff.getProgress() + 10;
            int hysVal = sbHys.getProgress() + 1;

            SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
            editor.putInt("t_on", tOnVal);
            editor.putInt("t_off", tOffVal);
            editor.putInt("hys", hysVal);
            editor.apply();

            Intent intent = new Intent("ACTION_SEND_PARAMS");
            intent.putExtra("t_on", tOnVal);
            intent.putExtra("t_off", tOffVal);
            intent.putExtra("hys", hysVal);
            intent.setPackage(getPackageName());
            sendBroadcast(intent);

            Toast.makeText(this, "参数已保存", Toast.LENGTH_SHORT).show();
            finish();
        });

        btnUnbind.setOnClickListener(v -> {
            // 通过广播请求 MainActivity 调用 BLE 服务解绑当前设备
            sendBroadcast(new Intent("ACTION_UNBIND").setPackage(getPackageName()));
            Toast.makeText(this, "已请求解绑", Toast.LENGTH_SHORT).show();
            finish();
        });

        btnForceUnbind.setOnClickListener(v -> {
            // 强制解绑：仅在本地清除绑定记录 + 重置 appId，不与设备通信（设备丢失场景）
            new AlertDialog.Builder(this)
                    .setTitle("强制解绑 app")
                    .setMessage("将清除本机绑定记录并重置 app 身份，用于 CH572 设备丢失场景。"
                            + "执行后旧设备绑定彻底作废，app 可绑定新设备。此操作不可撤销，是否继续？")
                    .setPositiveButton("确定强制解绑", (d, w) -> {
                        sendBroadcast(new Intent("ACTION_FORCE_UNBIND").setPackage(getPackageName()));
                        Toast.makeText(this, "已强制解绑 app", Toast.LENGTH_LONG).show();
                        finish();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        btnBack.setOnClickListener(v -> finish());
    }

    private void updateLabels(int tOn, int tOff, int hys) {
        tvTOnValue.setText(tOn + "%");
        tvTOffValue.setText(tOff + "%");
        tvHysValue.setText(hys + "%");
    }
}
