package com.probiotics.xiaoni;

import android.app.Activity;
import android.os.Bundle;

public class BaseActivity extends Activity {
    private static final String TRANSPARENCY_KEY = "window_transparency";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyTransparency();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyTransparency();
    }

    private void applyTransparency() {
        int transparency = getSharedPreferences("settings", MODE_PRIVATE).getInt(TRANSPARENCY_KEY, 100);
        float alpha = transparency / 100f;
        getWindow().getAttributes().alpha = alpha;
    }
}
