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
 * 보면서 바로 맞추는 작은 조절 바: 위상, 추적 세기, 밝기, 대비.
 *
 * 평소에는 화면 오른쪽 위에 작은 "3D 조절" 버튼만 있고, 누르면 그 아래에 바가 열린다
 * (화면을 많이 가리지 않게 폭은 화면의 절반 남짓). 버튼과 바 밖의 터치는 그대로
 * 아래 화면으로 간다.
 *
 * 위상은 한 바퀴(0~1)를 도는 값이라 끝에서 넘어가면 반대쪽에서 이어진다. −/+ 는 한 칸씩,
 * 누르고 있으면 계속 움직인다. 추적 세기는 이 필름에서 맞다고 확인한 1.5~2.5 안에서만.
 *
 * 밝기·대비가 있는 이유: 필름 3D 는 각 눈에 자기 몫의 화소만 가고 두 그림 사이에 어두운
 * 틈이 있어서 2D 보다 눈에 띄게 어둡다. 화면 밝기는 이미 최대라, 그림 쪽을 올린다.
 */
final class GlassTuner extends FrameLayout {

    interface Listener {
        /** 값이 바뀔 때마다. 저장과 다시 그리기는 받는 쪽이 한다. */
        void onTune(float phase, float gap, float boost, float contrast);
    }

    /** 밝기: 1 = 그대로, 클수록 어두운 쪽·중간을 끌어올림 (흰색은 흰색 그대로). */
    static final float MIN_BOOST = 1f;
    static final float MAX_BOOST = 2f;
    /** 대비: 1 = 그대로, 클수록 밝은 곳은 더 밝게, 어두운 곳은 더 어둡게. */
    static final float MIN_CONTRAST = 0.8f;
    static final float MAX_CONTRAST = 1.6f;

    private static final int SEEK_MAX = 1000;
    private static final int PHASE = 0;
    private static final int GAP = 1;
    private static final int BOOST = 2;
    private static final int CONTRAST = 3;
    private final String[] names;
    private static final float[] STEPS = {0.01f, 0.01f, 0.02f, 0.02f};

    private final Listener listener;
    private final float[] min;
    private final float[] max;
    private final float[] value = new float[4];
    private final TextView[] text = new TextView[4];
    private final SeekBar[] seek = new SeekBar[4];
    private final Handler repeat = new Handler(Looper.getMainLooper());

    private final Button toggle;
    private LinearLayout panel;     // 버튼의 람다가 읽으므로 final 이 아니다
    private boolean syncing;

    /**
     * The bar closes by itself: this long after the finger lifts once something was changed,
     * and after IDLE_CLOSE_MS when it was only opened or touched without a change
     * (as in DepthFlix; a bar left open sits over the game).
     */
    private static final long AUTO_CLOSE_MS = 1500;
    private static final long IDLE_CLOSE_MS = 5000;
    private final Runnable autoClose = () -> setOpen(false);
    /** A finger is down on the bar or its button: never close under it. */
    private boolean touching;
    /** Something was changed since the bar opened. */
    private boolean changed;

    GlassTuner(Context c, float phase, float gap, float boost, float contrast,
               float minGap, float maxGap, Listener listener) {
        super(c);
        this.listener = listener;
        names = new String[]{c.getString(com.limelight.R.string.glass_tune_phase), c.getString(com.limelight.R.string.glass_tune_track),
                c.getString(com.limelight.R.string.glass_tune_bright), c.getString(com.limelight.R.string.glass_tune_contrast)};
        min = new float[]{0f, minGap, MIN_BOOST, MIN_CONTRAST};
        max = new float[]{1f, maxGap, MAX_BOOST, MAX_CONTRAST};

        toggle = new Button(c);
        toggle.setText(com.limelight.R.string.glass_tune);
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
        // Top to bottom: brightness, contrast, tracking, phase (as in DepthFlix).
        for (int i : new int[]{BOOST, CONTRAST, GAP, PHASE}) {
            text[i] = label();
            seek[i] = seekBar(i);
            panel.addView(row(i));
        }

        LayoutParams pl = new LayoutParams(dp(360), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        pl.setMargins(0, dp(42), dp(8), 0);
        addView(panel, pl);
        panel.setVisibility(GONE);

        setValues(phase, gap, boost, contrast);
    }

    /** 다른 곳(도구 등)에서 값이 바뀌었을 때 표시를 맞춘다. */
    void setValues(float phase, float gap, float boost, float contrast) {
        value[PHASE] = wrap(phase);
        value[GAP] = clamp(GAP, gap);
        value[BOOST] = clamp(BOOST, boost);
        value[CONTRAST] = clamp(CONTRAST, contrast);
        sync();
    }

    void setOpen(boolean open) {
        removeCallbacks(autoClose);
        changed = false;
        panel.setVisibility(open ? VISIBLE : GONE);
        toggle.setText(open ? com.limelight.R.string.glass_tune_close : com.limelight.R.string.glass_tune);
        if (open) postDelayed(autoClose, IDLE_CLOSE_MS);
    }

    private void armAutoClose() {
        removeCallbacks(autoClose);
        if (panel.getVisibility() == VISIBLE) {
            postDelayed(autoClose, changed ? AUTO_CLOSE_MS : IDLE_CLOSE_MS);
        }
    }

    /**
     * Watches every touch that lands on the bar or its button, to hold the bar open while a
     * finger is on it and to time its closing from the moment the finger lifts.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        boolean handled = super.dispatchTouchEvent(e);
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            if (handled) {
                touching = true;
                removeCallbacks(autoClose);
            }
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            if (touching) {
                touching = false;
                armAutoClose();
            }
        }
        return handled;
    }

    private void set(int which, float v) {
        value[which] = which == PHASE ? wrap(v) : clamp(which, v);
        sync();
        changed = true;
        if (!touching) armAutoClose();
        listener.onTune(value[PHASE], value[GAP], value[BOOST], value[CONTRAST]);
    }

    private void sync() {
        syncing = true;
        for (int i = 0; i < 4; i++) {
            text[i].setText(String.format(Locale.US, "%s %.2f", names[i], value[i]));
            seek[i].setProgress(Math.round((value[i] - min[i]) / (max[i] - min[i]) * SEEK_MAX));
        }
        syncing = false;
    }

    private LinearLayout row(int which) {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        // 88 dp: "Contrast 1.15" wrapped onto two lines at 64 dp.
        r.addView(text[which], new LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.WRAP_CONTENT));
        r.addView(stepButton("−", which, -1), new LinearLayout.LayoutParams(dp(34), dp(30)));
        r.addView(seek[which], new LinearLayout.LayoutParams(0, dp(30), 1f));
        r.addView(stepButton("+", which, +1), new LinearLayout.LayoutParams(dp(34), dp(30)));
        return r;
    }

    /** 한 번 누르면 한 칸, 누르고 있으면 계속. */
    @SuppressLint("ClickableViewAccessibility")
    private Button stepButton(String label, final int which, final int sign) {
        Button b = new Button(getContext());
        b.setText(label);
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
            set(which, value[which] + sign * STEPS[which]);
            repeat.postDelayed(tick[0], 80);
        };
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    set(which, value[which] + sign * STEPS[which]);
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

    private SeekBar seekBar(final int which) {
        SeekBar s = new SeekBar(getContext());
        s.setMax(SEEK_MAX);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                if (user && !syncing) {
                    set(which, min[which] + (max[which] - min[which]) * p / SEEK_MAX);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        return s;
    }

    private GradientDrawable round(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(8));
        return d;
    }

    private float clamp(int which, float v) {
        return Math.max(min[which], Math.min(max[which], v));
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
        removeCallbacks(autoClose);
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
