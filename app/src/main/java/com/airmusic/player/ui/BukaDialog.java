package com.airmusic.player.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.airmusic.player.R;

import java.util.ArrayList;
import java.util.List;
import com.airmusic.player.ui.BukaDialog;

/**
 * 自己写的对话框，不使用系统 AlertDialog：卡片、圆角、配色、按钮、进出动效
 * 全部由应用控制。
 *
 * <p>用法与 AlertDialog.Builder 类似：
 * <pre>
 * BukaDialog.message(this, "标题", "内容").show();
 * BukaDialog.confirm(this, "标题", "内容", "确认", () -&gt; {...}).show();
 * BukaDialog.singleChoice(this, "播放方式", labels, checked, which -&gt; {...}).show();
 * </pre>
 */
public class BukaDialog extends Dialog {

    public interface OnText {
        void onText(String value);
    }

    public interface OnChoice {
        void onChoice(int index);
    }

    private final TextView title;
    private final TextView message;
    private final EditText input;
    private final ScrollView scroll;
    private final LinearLayout options;
    private final LinearLayout loadingRow;
    private final BukaSpinner spinner;
    private final TextView loadingText;
    private final TextView negative;
    private final TextView positive;
    private final View card;

    private final List<TextView> optionViews = new ArrayList<>();
    private boolean dismissing;

    private BukaDialog(Context context) {
        super(context, R.style.BukaDialogTheme);
        setContentView(R.layout.dialog_buka);
        card = findViewById(R.id.buka_card);
        // 卡片也跟着当前封面主色走
        card.setBackground(ColorTheme.dialogCard(context));
        title = findViewById(R.id.buka_title);
        message = findViewById(R.id.buka_message);
        input = findViewById(R.id.buka_input);
        input.setBackground(ColorTheme.optionIdle(context));
        scroll = findViewById(R.id.buka_scroll);
        options = findViewById(R.id.buka_options);
        loadingRow = findViewById(R.id.buka_loading_row);
        spinner = findViewById(R.id.buka_spinner);
        spinner.setSpinnerColor(ColorTheme.accent());
        loadingText = findViewById(R.id.buka_loading_text);
        negative = findViewById(R.id.buka_negative);
        positive = findViewById(R.id.buka_positive);

        PressFx.attach(negative);
        PressFx.attach(positive);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.62f);
            WindowManager.LayoutParams params = window.getAttributes();
            params.gravity = Gravity.CENTER;
            window.setAttributes(params);
            window.setLayout(dialogWidth(context), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        setCanceledOnTouchOutside(true);
    }

    private static int dialogWidth(Context context) {
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int max = Math.round(520f * metrics.density);          // 电视上别铺得太宽
        return Math.min(metrics.widthPixels - Math.round(32f * metrics.density), max);
    }

    // ------------------------------------------------------------------
    // 构造入口
    // ------------------------------------------------------------------

    public static BukaDialog message(Context context, CharSequence title, CharSequence text) {
        BukaDialog dialog = new BukaDialog(context);
        dialog.setTitleText(title);
        dialog.message.setText(text);
        dialog.message.setVisibility(View.VISIBLE);
        dialog.positive.setText(android.R.string.ok);
        dialog.positive.setBackground(ColorTheme.capsule(context));
        dialog.positive.setOnClickListener(v -> dialog.dismiss());
        dialog.negative.setVisibility(View.GONE);
        return dialog;
    }

    public static BukaDialog confirm(Context context, CharSequence title, CharSequence text,
                                     CharSequence confirmText, Runnable onConfirm) {
        BukaDialog dialog = new BukaDialog(context);
        dialog.setTitleText(title);
        if (text != null && text.length() > 0) {
            dialog.message.setText(text);
            dialog.message.setVisibility(View.VISIBLE);
        }
        dialog.positive.setText(confirmText);
        dialog.positive.setBackground(ColorTheme.capsule(context));
        dialog.positive.setOnClickListener(v -> {
            dialog.dismiss();
            if (onConfirm != null) onConfirm.run();
        });
        dialog.negative.setText(android.R.string.cancel);
        dialog.negative.setBackground(ColorTheme.capsule(context));
        dialog.negative.setOnClickListener(v -> dialog.dismiss());
        return dialog;
    }

    public static BukaDialog input(Context context, CharSequence title, CharSequence hint,
                                  String initial, CharSequence confirmText, OnText onConfirm) {
        BukaDialog dialog = new BukaDialog(context);
        dialog.setTitleText(title);
        dialog.input.setHint(hint);
        dialog.input.setText(initial == null ? "" : initial);
        dialog.input.setInputType(InputType.TYPE_CLASS_TEXT);
        dialog.input.setVisibility(View.VISIBLE);
        dialog.input.setSelection(dialog.input.getText().length());
        dialog.positive.setText(confirmText);
        dialog.positive.setBackground(ColorTheme.capsule(context));
        dialog.positive.setOnClickListener(v -> {
            String value = dialog.input.getText().toString();
            dialog.dismiss();
            if (onConfirm != null) onConfirm.onText(value);
        });
        dialog.negative.setText(android.R.string.cancel);
        dialog.negative.setBackground(ColorTheme.capsule(context));
        dialog.negative.setOnClickListener(v -> dialog.dismiss());
        return dialog;
    }

    /** 单选列表：点一项立即关闭并回调。 */
    public static BukaDialog singleChoice(Context context, CharSequence title, String[] items,
                                         int checked, OnChoice onPick) {
        BukaDialog dialog = new BukaDialog(context);
        dialog.setTitleText(title);
        for (int i = 0; i < items.length; i++) {
            final int index = i;
            dialog.addOption(items[i], i == checked, false, () -> {
                if (onPick != null) onPick.onChoice(index);
                dialog.dismiss();
            });
        }
        dialog.negative.setText(android.R.string.cancel);
        dialog.negative.setBackground(ColorTheme.capsule(context));
        dialog.negative.setOnClickListener(v -> dialog.dismiss());
        dialog.positive.setVisibility(View.GONE);
        return dialog;
    }

    /** 多选列表：勾选后点确认回调。 */
    public static BukaDialog multiChoice(Context context, CharSequence title, String[] items,
                                        boolean[] checked, CharSequence confirmText,
                                        OnMultiChoice onConfirm) {
        BukaDialog dialog = new BukaDialog(context);
        dialog.setTitleText(title);
        final boolean[] state = checked == null ? new boolean[items.length] : checked;
        for (int i = 0; i < items.length; i++) {
            final int index = i;
            dialog.addOption(items[i], state[i], true, () -> {
                state[index] = !state[index];
                dialog.refreshOptions(state);
            });
        }
        dialog.positive.setText(confirmText);
        dialog.positive.setBackground(ColorTheme.capsule(context));
        dialog.positive.setOnClickListener(v -> {
            dialog.dismiss();
            if (onConfirm != null) onConfirm.onConfirm(state);
        });
        dialog.negative.setText(android.R.string.cancel);
        dialog.negative.setBackground(ColorTheme.capsule(context));
        dialog.negative.setOnClickListener(v -> dialog.dismiss());
        return dialog;
    }

    public interface OnMultiChoice {
        void onConfirm(boolean[] checked);
    }

    /** 载入中：无按钮、不可点外部关闭。 */
    public static BukaDialog loading(Context context, CharSequence text) {
        BukaDialog dialog = new BukaDialog(context);
        dialog.title.setVisibility(View.GONE);
        dialog.message.setVisibility(View.GONE);
        dialog.loadingRow.setVisibility(View.VISIBLE);
        dialog.loadingText.setText(text);
        dialog.negative.setVisibility(View.GONE);
        dialog.positive.setVisibility(View.GONE);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setCancelable(false);
        return dialog;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private void setTitleText(CharSequence text) {
        boolean empty = text == null || text.length() == 0;
        title.setText(empty ? "" : text);
        title.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void addOption(String label, boolean selected, boolean keepOpen, Runnable onClick) {
        Context context = getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(selected
                ? ColorTheme.optionSelected(context) : ColorTheme.optionIdle(context));
        int padH = Math.round(16f * context.getResources().getDisplayMetrics().density);
        int padV = Math.round(13f * context.getResources().getDisplayMetrics().density);
        row.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = Math.round(8f * context.getResources().getDisplayMetrics().density);

        TextView text = new TextView(context);
        text.setText(label);
        text.setTextSize(15f);
        text.setTextColor(context.getResources().getColor(
                selected ? R.color.accent : R.color.text_primary));
        text.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(text);

        TextView mark = new TextView(context);
        mark.setText("✓");
        mark.setTextSize(16f);
        mark.setTextColor(context.getResources().getColor(R.color.accent));
        mark.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        row.addView(mark);

        row.setOnClickListener(v -> onClick.run());
        PressFx.attach(row);
        options.addView(row, rowParams);
        optionViews.add(text);
        // 行上记住对勾视图，多选刷新时改它的可见性 / 行底色。
        row.setTag(mark);
        if (scroll.getVisibility() != View.VISIBLE) {
            scroll.setVisibility(View.VISIBLE);
        }
    }

    private void refreshOptions(boolean[] state) {
        for (int i = 0; i < optionViews.size() && i < state.length; i++) {
            View row = (View) optionViews.get(i).getParent();
            Object tag = row.getTag();
            if (tag instanceof TextView) {
                ((TextView) tag).setVisibility(state[i] ? View.VISIBLE : View.INVISIBLE);
            }
            row.setBackground(state[i]
                    ? ColorTheme.optionSelected(getContext())
                    : ColorTheme.optionIdle(getContext()));
            optionViews.get(i).setTextColor(getContext().getResources().getColor(
                    state[i] ? R.color.accent : R.color.text_primary));
        }
    }

    // ------------------------------------------------------------------
    // 进出动效
    // ------------------------------------------------------------------

    @Override
    public void show() {
        super.show();
        // 选项多时限制列表高度，避免卡片比屏幕还高。
        if (scroll.getVisibility() == View.VISIBLE) {
            scroll.post(() -> {
                int max = Math.round(getContext().getResources()
                        .getDisplayMetrics().heightPixels * 0.5f);
                if (scroll.getHeight() > max) {
                    ViewGroup.LayoutParams params = scroll.getLayoutParams();
                    params.height = max;
                    scroll.setLayoutParams(params);
                }
            });
        }
        card.setAlpha(0f);
        card.setScaleX(0.92f);
        card.setScaleY(0.92f);
        card.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(200L)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();
    }

    @Override
    public void dismiss() {
        if (dismissing) return;
        dismissing = true;
        card.animate()
                .alpha(0f).scaleX(0.96f).scaleY(0.96f)
                .setDuration(140L)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    dismissing = false;
                    dismissNow();
                })
                .start();
    }

    /** 立刻关闭，不走动画（窗口已经不可用时使用）。 */
    public void dismissNow() {
        dismissing = true;
        try {
            super.dismiss();
        } catch (Throwable ignored) {
        }
        dismissing = false;
    }
}
