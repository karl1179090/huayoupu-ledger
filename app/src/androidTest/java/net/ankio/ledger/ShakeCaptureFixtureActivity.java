package net.ankio.ledger;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Self-contained non-launcher fixture: the test APK can start it without the target's Kotlin runtime. */
public final class ShakeCaptureFixtureActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String mode = getIntent().getStringExtra("mode");
        if (mode == null) mode = "safe";
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(Color.WHITE);
        column.setPadding(40, 80, 40, 40);
        if (!"blank".equals(mode)) {
            TextView text = new TextView(this);
            text.setText("verification".equals(mode) ? "合成支付验证页 请输入验证码" : "合成账单详情\n测试商店\n支出 12.34 元\n支付成功");
            text.setTextSize(24f);
            text.setTextColor(Color.BLACK);
            column.addView(text);
        }
        if ("password".equals(mode) || "editable".equals(mode)) {
            EditText input = new EditText(this);
            input.setHint("password".equals(mode) ? "合成密码输入框" : "合成可编辑输入框");
            input.setInputType("password".equals(mode)
                    ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT);
            column.addView(input);
        }
        setContentView(column);
    }
}
