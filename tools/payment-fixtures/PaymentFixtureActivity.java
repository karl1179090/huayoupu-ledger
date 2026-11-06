package net.ankio.ledger.fixtures;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.atomic.AtomicBoolean;

/** Entirely synthetic accessibility trees for the ledger's external-app regression contract. */
public class PaymentFixtureActivity extends Activity {
    private String mode;
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        mode = getIntent().getStringExtra("mode");
        render();
    }
    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (intent.hasExtra("mode")) mode = intent.getStringExtra("mode");
        render();
    }
    private TextView label(LinearLayout parent, String value, boolean descriptionOnly) {
        TextView view = descriptionOnly ? new DescriptionLabel(value) : new TextView(this);
        view.setText(value); view.setTextSize(22); view.setTextColor(Color.rgb(20, 35, 32));
        view.setPadding(dp(8), dp(8), dp(8), dp(8));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }
    private void password(LinearLayout parent) {
        EditText field = new EditText(this);
        field.setHint("合成支付密码");
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setText("synthetic");
        parent.addView(field, new LinearLayout.LayoutParams(-1, dp(56)));
        field.clearFocus();
    }
    private void render() {
        String current = mode == null ? "readable" : mode;
        boolean cold = current.startsWith("lite_cold_");
        boolean hidden = current.equals("unreadable") || current.equals("lite_unreadable") || current.equals("framework") || current.equals("framework_secure");
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (current.equals("framework_secure")) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (hidden) {
            // INCLUDE_NOT_IMPORTANT_VIEWS traverses hidden ordinary children. Canvas has no semantic subtree.
            View opaque = new DrawOnlyLabel("支付成功", "支付金额 ¥12.34"); opaque.setBackgroundColor(Color.WHITE);
            setContentView(opaque); return;
        }
        LinearLayout root = new ColdRoot(cold);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(16), dp(36), dp(16), dp(24));
        root.setBackgroundColor(Color.WHITE); root.setFocusableInTouchMode(true);
        setContentView(root);
        String name = getClass().getName();
        if (name.endsWith("LauncherUI") || name.equals("com.alipay.mobile.quinox.LauncherActivity")) {
            label(root, "花有谱合成支付测试首页", false);
            label(root, "准备发起新付款", false);
            label(root, "仅供隔离模拟器回归，无真实账户或支付能力", false);
            return;
        }
        if (name.equals("com.tencent.mm.BillActivity")) {
            label(root, "合成历史账单详情", false);
            label(root, "交易金额 12.34 元", false);
            label(root, "交易时间 2026-10-17 02:32:51", false);
            if (current.equals("chat")) password(root);
            return;
        }
        boolean descriptionOnly = current.startsWith("lite_");
        LinearLayout body = root;
        int depth = current.equals("framework_too_deep") ? 76 : current.startsWith("framework_deep_") ? 40 : current.equals("framework_grouped") ? 4 : 0;
        for (int i = 0; i < depth; i++) {
            LinearLayout group = new LinearLayout(this);
            group.setOrientation(LinearLayout.VERTICAL);
            group.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            body.addView(group, new LinearLayout.LayoutParams(-1, -2));
            body = group;
        }
        label(body, "支付成功", descriptionOnly);
        label(body, "待合成测试商店确认收款", descriptionOnly);
        if (!current.equals("lite_coupon_only")) label(body, "支付金额 ¥12.34", descriptionOnly);
        if (current.equals("lite_coupons") || current.equals("lite_coupon_only")) {
            label(body, "优惠券 ¥100.00", descriptionOnly);
            label(body, "最高可领优惠券 ¥200.00", descriptionOnly);
        }
        if (current.equals("lite_multiple_amounts")) label(body, "实付款 ¥99.00", descriptionOnly);
        if (current.equals("readable") && name.contains("remittance")) {
            label(body, "转账时间 2026-10-17 02:32:51", false);
            label(body, "转账单号 10001073012026101701183202026521", false);
        }
        label(body, "完成", descriptionOnly);
        if (current.contains("password")) password(body);
        if (current.equals("framework_verify")) {
            label(body, "身份验证", false);
            EditText verify = new EditText(this); verify.setHint("合成验证码");
            body.addView(verify, new LinearLayout.LayoutParams(-1, dp(56)));
        }
        root.requestFocus();
    }
    private final class DescriptionLabel extends TextView {
        private final String description;
        DescriptionLabel(String value) { super(PaymentFixtureActivity.this); description = value; }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setText(null); info.setContentDescription(description);
        }
    }
    private final class DrawOnlyLabel extends View {
        private final String[] lines;
        DrawOnlyLabel(String... values) { super(PaymentFixtureActivity.this); lines = values; setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.BLACK); paint.setTextSize(dp(18));
            for (int i = 0; i < lines.length; i++) canvas.drawText(lines[i], dp(16), dp(80 + i * 40), paint);
        }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info); info.setText(null); info.setContentDescription(null);
        }
    }
    private final class ColdRoot extends LinearLayout {
        private final AtomicBoolean delayed = new AtomicBoolean();
        private final boolean cold;
        ColdRoot(boolean value) { super(PaymentFixtureActivity.this); cold = value; }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            if (cold && delayed.compareAndSet(false, true)) SystemClock.sleep(1000);
            super.onInitializeAccessibilityNodeInfo(info);
        }
    }
}
