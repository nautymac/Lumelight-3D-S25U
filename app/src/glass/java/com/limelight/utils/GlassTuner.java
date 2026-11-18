package com.limelight.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * 보면서 바로 맞추는 작은 조절 바: 위상과 추적 세기.
 *
 * 평소에는 화면 오른쪽 위에 작은 "3D 조절" 버튼만 있고, 누르면 그 아래에 두 줄짜리 바가
 * 열린다 (화면을 많이 가리지 않게 폭은 화면의 절반 남짓). 버튼과 바 밖의 터치는 그대로
 * 아래 화면으로 간다.
 *
 * 위상은 한 바퀴(0~1)를 도는 값이라 끝에서 넘어가면 반대쪽에서 이어진다. −/+ 는 0.01씩,
 * 누르고 있으면 계속 움직인다. 추적 세기는 이 필름에서 맞다고 확인한 1.5~2.5 안에서만.
 */
final class GlassTuner extends FrameLayout {

    interface Listener {
        /** 값이 바뀔 때마다. 저장과 다시 그리기는 받는 쪽이 한다. */
        void onTune(float phase, float gap);
    }

    private static final int SEEK_MAX = 1000;
    private static final float PHASE_STEP = 0.01f;
    private static final float GAP_STEP = 0.01f;

    private final Listener listener;
    private final float minGap;
    private final float maxGap;
    private final Handler repeat = new Handler(Looper.getMainLooper());

    private float phase;
    private float gap;
    private final Button toggle;
    private LinearLayout panel;     // 버튼의 람다가 읽으므로 final 이 아니다
    private final TextView phaseText;
    private final TextView gapText;
    private final SeekBar phaseSeek;
    private final SeekBar gapSeek;
    private boolean syncing;

    GlassTuner(Context c, float phase, float gap, float minGap, float maxGap, Listener listener) {
        super(c);
        this.listener = listener;
        this.minGap = minGap;
        this.maxGap = maxGap;
        this.phase = wrap(phase);
        this.gap = clampGap(gap);

        toggle = new Button(c);
        toggle.setText("3D 조절");
        toggle.setAllCaps(false);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        toggle.setTextColor(Color.WHITE);
        toggle.setMinWidth(0);
        toggle.setMinHeight(0);
        toggle.setMinimumWidth(0);
        toggle.setMinimumHeight(0);
        toggle.setPadding(dp(10), dp(4), dp(10), dp(4));
        toggle.setBackground(round(0x99202020));
        toggle.setOnClickListener(v -> setOpen(panel.getVisibility() != VISIBLE));
        LayoutParams tl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30),
                Gravity.TOP | Gravity.END);
        tl.setMargins(0, dp(8), dp(8), 0);
        addView(toggle, tl);

        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(4), dp(8), dp(4));
        panel.setBackground(round(0xCC202020));
        panel.setClickable(true);   // 바 위의 터치가 아래 화면으로 새지 않게

        phaseText = label();
        phaseSeek = seek();
        panel.addView(row(phaseText, -1, phaseSeek, +1, true));
        gapText = label();
        gapSeek = seek();
        panel.addView(row(gapText, -1, gapSeek, +1, false));

        LayoutParams pl = new LayoutParams(dp(360), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        pl.setMargins(0, dp(42), dp(8), 0);
        addView(panel, pl);
        panel.setVisibility(GONE);

        phaseSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user && !syncing) { GlassTuner.this.phase = wrap(p / (float) SEEK_MAX); changed(); }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        gapSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user && !syncing) {
                    GlassTuner.this.gap = clampGap(GlassTuner.this.minGap
                            + (GlassTuner.this.maxGap - GlassTuner.this.minGap) * p / SEEK_MAX);
                    changed();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        sync();
    }

    /** 다른 곳(도구 등)에서 값이 바뀌었을 때 표시를 맞춘다. */
    void setValues(float phase, float gap) {
        this.phase = wrap(phase);
        this.gap = clampGap(gap);
        sync();
    }

    void setOpen(boolean open) {
        panel.setVisibility(open ? VISIBLE : GONE);
        toggle.setText(open ? "닫기" : "3D 조절");
    }

    private void nudge(boolean isPhase, int sign) {
        if (isPhase) {
            phase = wrap(phase + sign * PHASE_STEP);
        } else {
            gap = clampGap(gap + sign * GAP_STEP);
        }
        changed();
    }

    private void changed() {
        sync();
        listener.onTune(phase, gap);
    }

    private void sync() {
        syncing = true;
        phaseText.setText(String.format(Locale.US, "위상 %.2f", phase));
        gapText.setText(String.format(Locale.US, "추적 %.2f", gap));
        phaseSeek.setProgress(Math.round(phase * SEEK_MAX));
        gapSeek.setProgress(Math.round((gap - minGap) / (maxGap - minGap) * SEEK_MAX));
        syncing = false;
    }

    private LinearLayout row(TextView text, int minus, SeekBar seek, int plus, boolean isPhase) {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(text, new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
        r.addView(stepButton("−", isPhase, minus), new LinearLayout.LayoutParams(dp(34), dp(30)));
        r.addView(seek, new LinearLayout.LayoutParams(0, dp(30), 1f));
        r.addView(stepButton("+", isPhase, plus), new LinearLayout.LayoutParams(dp(34), dp(30)));
        return r;
    }

    /** 한 번 누르면 한 칸, 누르고 있으면 계속. */
    @SuppressLint("ClickableViewAccessibility")
    private Button stepButton(String text, final boolean isPhase, final int sign) {
        Button b = new Button(getContext());
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(Color.WHITE);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        b.setPadding(0, 0, 0, 0);
        b.setBackground(round(0x66FFFFFF));
        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            nudge(isPhase, sign);
            repeat.postDelayed(tick[0], 80);
        };
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    nudge(isPhase, sign);
                    repeat.postDelayed(tick[0], 400);
                    v.setPressed(true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    repeat.removeCallbacks(tick[0]);
                    v.setPressed(false);
                    return true;
                default:
                    return true;
            }
        });
        return b;
    }

    private TextView label() {
        TextView t = new TextView(getContext());
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        return t;
    }

    private SeekBar seek() {
        SeekBar s = new SeekBar(getContext());
        s.setMax(SEEK_MAX);
        return s;
    }

    private GradientDrawable round(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(8));
        return d;
    }

    private float clampGap(float g) {
        return Math.max(minGap, Math.min(maxGap, g));
    }

    private static float wrap(float p) {
        float w = p % 1f;
        return w < 0 ? w + 1f : w;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDetachedFromWindow() {
        repeat.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    /** 바와 버튼 밖의 빈 곳은 터치를 가져가지 않는다. */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return false;
    }

    @Override
    public void setVisibility(int visibility) {
        if (visibility != VISIBLE) {
            repeat.removeCallbacksAndMessages(null);
        }
        super.setVisibility(visibility);
    }
}
