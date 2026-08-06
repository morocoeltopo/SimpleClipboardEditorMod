package com.trianguloy.clipboardeditor;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;

public class ClipboardAccessibilityService extends AccessibilityService {
    private static final String TAG = "ClipboardAccessService";

    private WindowManager windowManager;
    private ClipboardManager clipboardManager;
    private ClipboardManager.OnPrimaryClipChangedListener clipListener;

    private View bubbleView;
    private WindowManager.LayoutParams bubbleParams;

    private View editorView;
    private WindowManager.LayoutParams editorParams;

    private boolean isOurOwnCopy = false;
    private String lastCopiedText = "";

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        clipboardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);

        clipListener = () -> {
            if (isOurOwnCopy) {
                isOurOwnCopy = false;
                return;
            }

            Preferences prefs = new Preferences(getSharedPreferences(Preferences.PREFS_NAME, MODE_PRIVATE));
            boolean enabled = prefs.is(Preferences.Pref.FLOATING_BUBBLE);
            if (!enabled) {
                return;
            }

            ClipData clip = clipboardManager.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence textChar = clip.getItemAt(0).coerceToText(this);
                if (textChar != null) {
                    String text = textChar.toString();
                    if (!text.isEmpty() && !text.equals(lastCopiedText)) {
                        lastCopiedText = text;
                        showBubble(text);
                    }
                }
            }
        };
        clipboardManager.addPrimaryClipChangedListener(clipListener);
    }

    private void showBubble(final String text) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (editorView != null) {
                    EditText contentEdit = editorView.findViewById(R.id.floating_content);
                    if (contentEdit != null) {
                        contentEdit.setText(text);
                        contentEdit.setSelection(text.length());
                    }
                    return;
                }

                dismissBubble();

                bubbleView = LayoutInflater.from(this).inflate(R.layout.floating_bubble, null);

                int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;

                bubbleParams = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        layoutType,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                );

                bubbleParams.gravity = Gravity.BOTTOM | Gravity.END;
                bubbleParams.x = 50;
                bubbleParams.y = 300;

                bubbleView.setOnTouchListener(new View.OnTouchListener() {
                    private int initialX;
                    private int initialY;
                    private float initialTouchX;
                    private float initialTouchY;
                    private boolean isMoving = false;

                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        switch (event.getAction()) {
                            case MotionEvent.ACTION_DOWN:
                                initialX = bubbleParams.x;
                                initialY = bubbleParams.y;
                                initialTouchX = event.getRawX();
                                initialTouchY = event.getRawY();
                                isMoving = false;
                                return true;
                            case MotionEvent.ACTION_MOVE:
                                int dx = (int) (event.getRawX() - initialTouchX);
                                int dy = (int) (event.getRawY() - initialTouchY);
                                bubbleParams.x = initialX - dx;
                                bubbleParams.y = initialY - dy;
                                if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                                    isMoving = true;
                                }
                                try {
                                    windowManager.updateViewLayout(bubbleView, bubbleParams);
                                } catch (Exception e) {
                                    Log.e(TAG, "Error updating bubble layout", e);
                                }
                                return true;
                            case MotionEvent.ACTION_UP:
                                if (!isMoving) {
                                    v.performClick();
                                    showEditor(text);
                                }
                                return true;
                        }
                        return false;
                    }
                });

                windowManager.addView(bubbleView, bubbleParams);
            } catch (Exception e) {
                Log.e(TAG, "Failed to show bubble", e);
            }
        });
    }

    private void showEditor(final String text) {
        dismissBubble();

        editorView = LayoutInflater.from(this).inflate(R.layout.floating_editor, null);

        final EditText contentEdit = editorView.findViewById(R.id.floating_content);
        contentEdit.setText(text);
        if (text != null) {
            contentEdit.setSelection(text.length());
        }

        Button btnCancel = editorView.findViewById(R.id.btn_cancel);
        Button btnSave = editorView.findViewById(R.id.btn_save);

        int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        editorParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        editorParams.gravity = Gravity.CENTER;

        btnCancel.setOnClickListener(v -> dismissEditor());

        btnSave.setOnClickListener(v -> {
            String editedText = contentEdit.getText().toString();
            isOurOwnCopy = true;
            lastCopiedText = editedText;

            ClipData clip = ClipData.newPlainText("", editedText);
            clipboardManager.setPrimaryClip(clip);

            dismissEditor();
        });

        try {
            windowManager.addView(editorView, editorParams);

            contentEdit.requestFocus();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(contentEdit, InputMethodManager.SHOW_IMPLICIT);
                }
            }, 100);

        } catch (Exception e) {
            Log.e(TAG, "Failed to show editor", e);
        }
    }

    private void dismissBubble() {
        if (bubbleView != null) {
            try {
                windowManager.removeView(bubbleView);
            } catch (Exception e) {
                // Ignore
            }
            bubbleView = null;
        }
    }

    private void dismissEditor() {
        if (editorView != null) {
            try {
                windowManager.removeView(editorView);
            } catch (Exception e) {
                // Ignore
            }
            editorView = null;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (isOurOwnCopy) {
            try {
                if (clipboardManager != null) {
                    ClipData clip = clipboardManager.getPrimaryClip();
                    if (clip != null && clip.getItemCount() > 0) {
                        CharSequence textChar = clip.getItemAt(0).coerceToText(this);
                        if (textChar != null && textChar.toString().equals(lastCopiedText)) {
                            isOurOwnCopy = false;
                            return;
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore
            }
            isOurOwnCopy = false;
        }

        Preferences prefs = new Preferences(getSharedPreferences(Preferences.PREFS_NAME, MODE_PRIVATE));
        boolean enabled = prefs.is(Preferences.Pref.FLOATING_BUBBLE);
        if (!enabled) {
            return;
        }

        try {
            if (clipboardManager != null) {
                ClipData clip = clipboardManager.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence textChar = clip.getItemAt(0).coerceToText(this);
                    if (textChar != null) {
                        String text = textChar.toString();
                        if (!text.isEmpty() && !text.equals(lastCopiedText)) {
                            lastCopiedText = text;
                            showBubble(text);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking clipboard in onAccessibilityEvent", e);
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        dismissBubble();
        dismissEditor();
        if (clipboardManager != null && clipListener != null) {
            clipboardManager.removePrimaryClipChangedListener(clipListener);
        }
    }
}
