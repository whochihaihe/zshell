/*
*************************************************************************
vShell - x86 Linux virtual shell application powered by QEMU.
Copyright (C) 2019-2021  Leonid Pliushch <leonid.pliushch@gmail.com>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
*************************************************************************
*/
package app.virtshell;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.autofill.AutofillManager;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.virtshell.emulator.TerminalSession;
import app.virtshell.emulator.TerminalSession.SessionChangedCallback;
import app.virtshell.terminal_view.TerminalView;

public final class TerminalActivity extends Activity implements ServiceConnection {

    private static final int CONTEXTMENU_PASTE_ID = 1;
    private static final int CONTEXTMENU_SHOW_HELP = 2;
    private static final int CONTEXTMENU_OPEN_SSH = 3;
    private static final int CONTEXTMENU_OPEN_WEB = 4;
    private static final int CONTEXTMENU_AUTOFILL_PW = 5;
    private static final int CONTEXTMENU_SELECT_URLS = 6;
    private static final int CONTEXTMENU_RESET_TERMINAL_ID = 7;
    private static final int CONTEXTMEMU_SHUTDOWN = 8;
    private static final int CONTEXTMENU_TOGGLE_IGNORE_BELL = 9;

    private final int MAX_FONTSIZE = 256;
    private int MIN_FONTSIZE;
    private static int currentFontSize = -1;

    TerminalPreferences mSettings;
    TerminalView mTerminalView;
    ExtraKeysView mExtraKeysView;
    TerminalService mTermService;
    private boolean mIsVisible;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.main);

        mTerminalView = findViewById(R.id.terminal_view);
        mTerminalView.setOnKeyListener(new InputDispatcher(this));
        mTerminalView.setKeepScreenOn(true);
        mTerminalView.requestFocus();
        setupTerminalStyle();
        registerForContextMenu(mTerminalView);

        mSettings = new TerminalPreferences(this);
        mExtraKeysView = findViewById(R.id.extra_keys);
        if (mSettings.isExtraKeysEnabled()) {
            mExtraKeysView.setVisibility(View.VISIBLE);
        }

        if (mSettings.isFirstRun()) {
            new AlertDialog.Builder(this)
                .setCancelable(false)
                .setTitle(R.string.firstrun_dialog_title)
                .setMessage(R.string.firstrun_dialog_desc)
                .setPositiveButton(R.string.ok_label, (dialog, which) -> {
                    dialog.dismiss();
                    mSettings.completedFirstRun(this);
                    startApplication();
            }).show();
        } else {
            startApplication();
        }
    }

    private void startApplication() {
        boolean hasStoragePermission = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                hasStoragePermission = true;
            }
        } else {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
                hasStoragePermission = true;
            }
        }

        if (!hasStoragePermission) {
            startActivity(new Intent(this, StoragePermissionActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }

        Intent serviceIntent = new Intent(this, TerminalService.class);
        startService(serviceIntent);
        if (!bindService(serviceIntent, this, 0)) {
            throw new RuntimeException("bindService() failed");
        }
    }

    private void setupTerminalStyle() {
        float dipInPixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1,
            getResources().getDisplayMetrics());
        int defaultFontSize = Math.round(7.5f * dipInPixels);

        if (defaultFontSize % 2 == 1) defaultFontSize--;

        if (TerminalActivity.currentFontSize == -1) {
            TerminalActivity.currentFontSize = defaultFontSize;
        }

        MIN_FONTSIZE = (int) (4f * dipInPixels);

        TerminalActivity.currentFontSize = Math.max(MIN_FONTSIZE,
            Math.min(TerminalActivity.currentFontSize, MAX_FONTSIZE));
        mTerminalView.setTextSize(TerminalActivity.currentFontSize);

        mTerminalView.setTypeface(Typeface.createFromAsset(getAssets(), "console_font.ttf"));
    }

    @Override
    protected void onStart() {
        super.onStart();
        mIsVisible = true;

        if (mTermService != null) {
            TerminalSession session = mTermService.getSession();
            if (session != null) {
                mTerminalView.attachSession(session);
            }
        }

        mTerminalView.onScreenUpdated();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mIsVisible = false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mTermService != null) {
            mTermService.mSessionChangeCallback = null;
            mTermService = null;
            unbindService(this);
        }
    }

    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        mTermService = ((TerminalService.LocalBinder) service).service;

        mTermService.mSessionChangeCallback = new SessionChangedCallback() {
            @Override
            public void onTextChanged(TerminalSession changedSession) {
                if (!mIsVisible) return;
                if (mTerminalView.getCurrentSession() == changedSession) {
                    mTerminalView.onScreenUpdated();
                }
            }

            @Override
            public void onSessionFinished(final TerminalSession finishedSession) {
                TerminalActivity.currentFontSize = -1;

                if (!BuildConfig.DEBUG) {
                    if (mTermService.mWantsToStop) {
                        if (!TerminalActivity.this.isFinishing()) {
                            finish();
                        }
                        return;
                    }
                    mTermService.terminateService();
                }
            }

            @Override
            public void onClipboardText(TerminalSession session, String text) {
                if (!mIsVisible) return;
                ClipboardManager clipboard = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(new ClipData(null,
                        new String[]{"text/plain"}, new ClipData.Item(text)));
                }
            }

            @Override
            public void onBell(TerminalSession session) {
                if (!mIsVisible || mSettings.isBellIgnored()) {
                    return;
                }

                Bell.getInstance(TerminalActivity.this).doBell();
            }
        };

        if (mTermService.getSession() == null) {
            if (mIsVisible) {
                Installer.setupIfNeeded(TerminalActivity.this, () -> {
                    if (mTermService == null) return;

                    try {
                        TerminalSession session = startQemu();
                        mTerminalView.attachSession(session);
                        mTermService.setSession(session);
                    } catch (WindowManager.BadTokenException e) {
                        // Activity finished - ignore.
                    }
                });
            } else {
                if (!TerminalActivity.this.isFinishing()) {
                    finish();
                }
            }
        } else {
            mTerminalView.attachSession(mTermService.getSession());
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        if (!TerminalActivity.this.isFinishing()) {
            finish();
        }
    }

    private int[] getSafeMem() {
        Context appContext = this;
        ActivityManager am = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();

        if (am == null) {
            return new int[]{Config.QEMU_MIN_TCG_BUF, Config.QEMU_MIN_SAFE_RAM};
        }

        am.getMemoryInfo(memInfo);

        Log.i(Config.APP_LOG_TAG, "memory: " + memInfo.totalMem + " total, "
            + memInfo.availMem + " avail, " + memInfo.threshold + " oom threshold");
        Log.i(Config.APP_LOG_TAG, "system low on memory: " + memInfo.lowMemory);

        int safeMem = (int) ((memInfo.availMem * 0.8 - memInfo.threshold) / 1048576);

        int tcgAlloc = Math.min(Config.QEMU_MAX_TCG_BUF,
            Math.max(Config.QEMU_MIN_TCG_BUF, (int) (safeMem * 0.12)));
        int ramAlloc = Math.min(Config.QEMU_MAX_SAFE_RAM,
            Math.max(Config.QEMU_MIN_SAFE_RAM, (int) (safeMem - safeMem * 0.12)));

        Log.i(Config.APP_LOG_TAG, "calculated safe mem (tcg, ram): [" + tcgAlloc + ", " + ramAlloc + "]");

        return new int[]{tcgAlloc, ramAlloc};
    }

    private TerminalSession startQemu() {
        ArrayList<String> environment = new ArrayList<>();
        Context appContext = this;

        String runtimeDataPath = Config.getDataDirectory(appContext);

        environment.add("ANDROID_ROOT=" + System.getenv("ANDROID_ROOT"));
        environment.add("ANDROID_DATA=" + System.getenv("ANDROID_DATA"));
        environment.add("APP_RUNTIME_DIR=" + runtimeDataPath);
        environment.add("LANG=en_US.UTF-8");
        environment.add("HOME=" + runtimeDataPath);
        environment.add("PATH=/system/bin");
        environment.add("TMPDIR=" + appContext.getCacheDir().getAbsolutePath());

        environment.add("CONFIG_QEMU_DNS=" + Config.QEMU_UPSTREAM_DNS_V4);
        environment.add("CONFIG_QEMU_DNS6=" + Config.QEMU_UPSTREAM_DNS_V6);

        String[] androidExtra = {
            "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT", "ANDROID_RUNTIME_ROOT", "ANDROID_TZDATA_ROOT"
        };
        for (String var : androidExtra) {
            String value = System.getenv(var);
            if (value != null) {
                environment.add(var + "=" + value);
            }
        }

        ArrayList<String> processArgs = new ArrayList<>();
        processArgs.add("vShell");
        processArgs.addAll(Arrays.asList("-L", runtimeDataPath));
        processArgs.addAll(Arrays.asList("-cpu", "max"));

        int[] mem = getSafeMem();
        processArgs.addAll(Arrays.asList("-accel", "tcg,tb-size=" + mem[0], "-m", String.valueOf(mem[1])));
        processArgs.add("-nodefaults");

        processArgs.addAll(Arrays.asList("-drive", "file=" + runtimeDataPath + "/"
            + Config.CDROM_IMAGE_NAME + ",if=none,media=cdrom,index=0,id=cd0"));
        processArgs.addAll(Arrays.asList("-drive", "file=" + runtimeDataPath + "/"
            + Config.HDD_IMAGE_NAME
            + ",if=none,index=2,discard=unmap,detect-zeroes=unmap,cache=writeback,id=hd0"));
        processArgs.addAll(Arrays.asList("-device", "virtio-scsi-pci,id=virtio-scsi-pci0"));
        processArgs.addAll(Arrays.asList("-device",
            "scsi-cd,bus=virtio-scsi-pci0.0,id=scsi-cd0,drive=cd0"));
        processArgs.addAll(Arrays.asList("-device",
            "scsi-hd,bus=virtio-scsi-pci0.0,id=scsi-hd0,drive=hd0"));

        processArgs.addAll(Arrays.asList("-boot", "c,menu=on"));

        processArgs.addAll(Arrays.asList("-object", "rng-random,filename=/dev/urandom,id=rng0"));
        processArgs.addAll(Arrays.asList("-device", "virtio-rng-pci,rng=rng0,id=virtio-rng-pci0"));

        // 网络配置 - 使用固定端口转发
        String vmnicArgs = "user,id=vmnic0";
        
        // 从偏好设置获取端口配置
        int sshPort = mSettings.getSshPort();          // 8022
        int port5678 = mSettings.getPort5678();        // 5678
        int port5700 = mSettings.getPort5700();        // 5700
        int port6379 = mSettings.getPort6379();        // 6379
        int port9000 = mSettings.getPort9000();        // 9000

        // 配置所有端口转发规则
        String[] portForwards = {
            "hostfwd=tcp::" + sshPort + "-:22",        // SSH: 8022->22
            "hostfwd=tcp::" + port5678 + "-:5678",     // 5678->5678
            "hostfwd=tcp::" + port5700 + "-:5700",     // 5700->5700
            "hostfwd=tcp::" + port6379 + "-:6379",     // 6379->6379
            "hostfwd=tcp::" + port9000 + "-:9000"      // 9000->9000
        };
        
        // 将端口转发规则添加到网络配置
        vmnicArgs += "," + TextUtils.join(",", portForwards);

        // 更新服务中的端口信息
        mTermService.SSH_PORT = sshPort;
        mTermService.WEB_PORT = -1;
        mTermService.PORT_5678 = port5678;
        mTermService.PORT_5700 = port5700;
        mTermService.PORT_6379 = port6379;
        mTermService.PORT_9000 = port9000;

        processArgs.addAll(Arrays.asList("-netdev", vmnicArgs));
        processArgs.addAll(Arrays.asList("-device", "virtio-net-pci,netdev=vmnic0,id=virtio-net-pci0"));

        // 共享存储访问
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            processArgs.addAll(Arrays.asList("-fsdev",
                "local,security_model=mapped-file,id=fsdev0,multidevs=remap,path=/storage/self/primary"));
            processArgs.addAll(Arrays.asList("-device",
                "virtio-9p-pci,fsdev=fsdev0,mount_tag=host_storage,id=virtio-9p-pci0"));
        }

        processArgs.add("-nographic");
        processArgs.addAll(Arrays.asList("-parallel", "none"));
        processArgs.addAll(Arrays.asList("-chardev", "stdio,id=serial0,mux=off,signal=off"));
        processArgs.addAll(Arrays.asList("-serial", "chardev:serial0"));

        Log.i(Config.APP_LOG_TAG, "QEMU启动参数: " + processArgs.toString());

        TerminalSession session = new TerminalSession(processArgs.toArray(new String[0]),
            environment.toArray(new String[0]), Config.getDataDirectory(appContext), mTermService);

        Toast.makeText(this, R.string.toast_boot_notification, Toast.LENGTH_LONG).show();

        return session;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mTerminalView.showContextMenu();
        return false;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        menu.add(Menu.NONE, CONTEXTMENU_SHOW_HELP, Menu.NONE, R.string.menu_show_help);
        if (mTermService != null) {
            if (mTermService.SSH_PORT != -1) {
                menu.add(Menu.NONE, CONTEXTMENU_OPEN_SSH, Menu.NONE, 
                    getResources().getString(R.string.menu_open_ssh, "localhost:" + mTermService.SSH_PORT));
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AutofillManager autofillManager = getSystemService(AutofillManager.class);
            if (autofillManager != null && autofillManager.isEnabled()) {
                menu.add(Menu.NONE, CONTEXTMENU_AUTOFILL_PW, Menu.NONE, R.string.menu_autofill_pw);
            }
        }
        menu.add(Menu.NONE, CONTEXTMENU_SELECT_URLS, Menu.NONE, R.string.menu_select_urls);
        menu.add(Menu.NONE, CONTEXTMENU_RESET_TERMINAL_ID, Menu.NONE, R.string.menu_reset_terminal);
        menu.add(Menu.NONE, CONTEXTMEMU_SHUTDOWN, Menu.NONE, R.string.menu_shutdown);
        menu.add(Menu.NONE, CONTEXTMENU_TOGGLE_IGNORE_BELL, Menu.NONE, R.string.menu_toggle_ignore_bell)
            .setCheckable(true).setChecked(mSettings.isBellIgnored());
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case CONTEXTMENU_PASTE_ID:
                doPaste();
                return true;
            case CONTEXTMENU_SHOW_HELP:
                startActivity(new Intent(this, HelpActivity.class));
                return true;
            case CONTEXTMENU_OPEN_SSH:
                if (mTermService == null) {
                    return false;
                }

                if (mTermService.SSH_PORT != -1) {
                    AlertDialog.Builder prompt = new AlertDialog.Builder(this);
                    EditText userNameInput = new EditText(this);
                    userNameInput.setText(mSettings.getDefaultSshUser());
                    prompt.setTitle(R.string.dialog_set_ssh_user_title);
                    prompt.setView(userNameInput);

                    prompt.setPositiveButton(R.string.ok_label, (dialog, which) -> {
                        String userName = userNameInput.getText().toString();

                        if (!userName.matches("[a-z_][a-z0-9_-]{0,31}")) {
                            dialog.dismiss();
                            Toast.makeText(this, R.string.dialog_set_ssh_user_invalid_name, Toast.LENGTH_LONG).show();
                            return;
                        } else {
                            mSettings.setDefaultSshUser(this, userName);
                        }

                        String address = "ssh://" + userName + "@127.0.0.1:" + mTermService.SSH_PORT + "/#vShell";
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(address));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            startActivity(intent);
                        } catch (Exception e) {
                            Toast.makeText(this, R.string.toast_open_ssh_intent_failure, Toast.LENGTH_LONG).show();
                            Log.e(Config.APP_LOG_TAG, "启动SSH客户端失败", e);
                        }
                        dialog.dismiss();
                    }).setNegativeButton(R.string.cancel_label, ((dialog, which) -> dialog.dismiss())).show();
                } else {
                    Toast.makeText(this, R.string.toast_open_ssh_unavailable, Toast.LENGTH_LONG).show();
                }
                return true;
            case CONTEXTMENU_OPEN_WEB:
                Toast.makeText(this, R.string.toast_open_web_unavailable, Toast.LENGTH_LONG).show();
                return true;
            case CONTEXTMENU_AUTOFILL_PW:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    AutofillManager autofillManager = getSystemService(AutofillManager.class);
                    if (autofillManager != null && autofillManager.isEnabled()) {
                        autofillManager.requestAutofill(mTerminalView);
                    }
                }
                return true;
            case CONTEXTMENU_SELECT_URLS:
                showUrlSelection();
                return true;
            case CONTEXTMENU_RESET_TERMINAL_ID:
                TerminalSession session = mTerminalView.getCurrentSession();
                if (session != null) {
                    session.reset(true);
                    Toast.makeText(this, R.string.toast_reset_terminal,
                        Toast.LENGTH_SHORT).show();
                }
                return true;
            case CONTEXTMEMU_SHUTDOWN:
                if (mTermService != null) {
                    new AlertDialog.Builder(this)
                        .setTitle(R.string.dialog_shut_down_title)
                        .setMessage(R.string.dialog_shut_down_desc)
                        .setPositiveButton(R.string.dialog_shut_down_yes_btn, (dialog, which) -> {
                            dialog.dismiss();
                            mTermService.terminateService();
                        }).setNegativeButton(R.string.cancel_label,
                        ((dialog, which) -> dialog.dismiss())).show();
                }
                return true;
            case CONTEXTMENU_TOGGLE_IGNORE_BELL:
                mSettings.setIgnoreBellCharacter(this, !mSettings.isBellIgnored());
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    public void doPaste() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

        if (clipboard != null) {
            ClipData clipData = clipboard.getPrimaryClip();

            if (clipData == null) {
                return;
            }

            CharSequence paste = clipData.getItemAt(0).coerceToText(this);
            if (!TextUtils.isEmpty(paste)) {
                TerminalSession currentSession = mTerminalView.getCurrentSession();

                if (currentSession != null) {
                    currentSession.getEmulator().paste(paste.toString());
                }
            }
        }
    }

    public void showUrlSelection() {
        TerminalSession currentSession = mTerminalView.getCurrentSession();

        if (currentSession == null) {
            return;
        }

        String text = currentSession.getEmulator().getScreen().getTranscriptText();
        LinkedHashSet<CharSequence> urlSet = extractUrls(text);

        if (urlSet.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_urls_found, Toast.LENGTH_SHORT).show();
            return;
        }

        final CharSequence[] urls = urlSet.toArray(new CharSequence[0]);
        Collections.reverse(Arrays.asList(urls));

        final AlertDialog dialog = new AlertDialog.Builder(TerminalActivity.this)
            .setItems(urls, (di, which) -> {
                String url = (String) urls[which];
                ClipboardManager clipboard = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(new ClipData(null, new String[]{"text/plain"},
                        new ClipData.Item(url)));
                    Toast.makeText(this, R.string.toast_url_copied,
                        Toast.LENGTH_SHORT).show();
                }
        }).setTitle(R.string.select_url_dialog_title).create();

        dialog.setOnShowListener(di -> {
            ListView lv = dialog.getListView();
            lv.setOnItemLongClickListener((parent, view, position, id) -> {
                dialog.dismiss();
                String url = (String) urls[position];

                if (!url.startsWith("file://")) {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    try {
                        startActivity(i, null);
                    } catch (ActivityNotFoundException e) {
                        startActivity(Intent.createChooser(i, null));
                    }
                } else {
                    Toast.makeText(this, R.string.toast_bad_url, Toast.LENGTH_SHORT).show();
                }

                return true;
            });
        });

        dialog.show();
    }

    @SuppressWarnings("StringBufferReplaceableByString")
    private static LinkedHashSet<CharSequence> extractUrls(String text) {
        StringBuilder regex_sb = new StringBuilder();

        regex_sb.append("(");
        regex_sb.append("(?:");
        regex_sb.append("dav|dict|dns|file|finger|ftp(?:s?)|git|gemini|gopher|http(?:s?)|");
        regex_sb.append("imap(?:s?)|irc(?:[6s]?)|ip[fn]s|ldap(?:s?)|pop3(?:s?)|redis(?:s?)|");
        regex_sb.append("rsync|rtsp(?:[su]?)|sftp|smb(?:s?)|smtp(?:s?)|svn(?:(?:\\+ssh)?)|");
        regex_sb.append("tcp|telnet|tftp|udp|vnc|ws(?:s?)");
        regex_sb.append(")://");
        regex_sb.append(")");

        regex_sb.append("(");
        regex_sb.append("(?:\\S+(?::\\S*)?@)?");
        regex_sb.append("(?:");
        regex_sb.append("(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)|");
        regex_sb.append("(?:(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)(?:(?:\\.(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)*(?:\\.(?:[a-z\\u00a1-\\uffff]{2,})))?|");
        regex_sb.append("/(?:(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)");
        regex_sb.append(")");
        regex_sb.append("(?::\\d{1,5})?");
        regex_sb.append("(?:/[a-zA-Z0-9:@%\\-._~!$&()*+,;=?/]*)?");
        regex_sb.append("(?:#[a-zA-Z0-9:@%\\-._~!$&()*+,;=?/]*)?");
        regex_sb.append(")");

        final Pattern urlPattern = Pattern.compile(
            regex_sb.toString(),
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);

        LinkedHashSet<CharSequence> urlSet = new LinkedHashSet<>();
        Matcher matcher = urlPattern.matcher(text);

        while (matcher.find()) {
            int matchStart = matcher.start(1);
            int matchEnd = matcher.end();
            String url = text.substring(matchStart, matchEnd);
            urlSet.add(url);
        }

        return urlSet;
    }

    public void changeFontSize(boolean increase) {
        TerminalActivity.currentFontSize += (increase ? 1 : -1) * 2;
        TerminalActivity.currentFontSize = Math.max(MIN_FONTSIZE,
            Math.min(TerminalActivity.currentFontSize, MAX_FONTSIZE));
        mTerminalView.setTextSize(TerminalActivity.currentFontSize);
    }

    public void toggleShowExtraKeys() {
        View extraKeys = findViewById(R.id.extra_keys);
        boolean showNow = mSettings.toggleShowExtraKeys(TerminalActivity.this);
        extraKeys.setVisibility(showNow ? View.VISIBLE : View.GONE);
    }
}
    