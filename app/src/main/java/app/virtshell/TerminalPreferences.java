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

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

public class TerminalPreferences {

    private static final String PREF_FIRST_RUN = "first_run";
    private static final String PREF_SHOW_EXTRA_KEYS = "show_extra_keys";
    private static final String PREF_IGNORE_BELL = "ignore_bell";
    private static final String PREF_DATA_VERSION = "data_version";
    private static final String PREF_DEFAULT_SSH_USER = "default_ssh_user";
    
    // 端口相关的偏好设置键
    private static final String PREF_SSH_PORT = "ssh_port";
    private static final String PREF_PORT_5678 = "port_5678";
    private static final String PREF_PORT_5700 = "port_5700";
    private static final String PREF_PORT_6379 = "port_6379";
    private static final String PREF_PORT_9000 = "port_9000";

    private boolean mFirstRun;
    private boolean mShowExtraKeys;
    private boolean mIgnoreBellCharacter;
    private int mDataVersion;
    private String mDefaultSshUser;
    
    // 端口配置变量
    private int mSshPort;
    private int mPort5678;
    private int mPort5700;
    private int mPort6379;
    private int mPort9000;

    public TerminalPreferences(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        mFirstRun = prefs.getBoolean(PREF_FIRST_RUN, true);
        mShowExtraKeys = prefs.getBoolean(PREF_SHOW_EXTRA_KEYS, true);
        mIgnoreBellCharacter = prefs.getBoolean(PREF_IGNORE_BELL, false);
        mDataVersion = prefs.getInt(PREF_DATA_VERSION, 0);
        mDefaultSshUser = prefs.getString(PREF_DEFAULT_SSH_USER, "root");
        
        // 加载端口配置，使用默认值如果没有配置过
        mSshPort = prefs.getInt(PREF_SSH_PORT, 8022);
        mPort5678 = prefs.getInt(PREF_PORT_5678, 5678);
        mPort5700 = prefs.getInt(PREF_PORT_5700, 5700);
        mPort6379 = prefs.getInt(PREF_PORT_6379, 6379);
        mPort9000 = prefs.getInt(PREF_PORT_9000, 9000);
    }

    public boolean isFirstRun() {
        return mFirstRun;
    }

    public void completedFirstRun(Context context) {
        mFirstRun = false;
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PREF_FIRST_RUN, mFirstRun).apply();
    }

    public boolean isExtraKeysEnabled() {
        return mShowExtraKeys;
    }

    public boolean toggleShowExtraKeys(Context context) {
        mShowExtraKeys = !mShowExtraKeys;
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PREF_SHOW_EXTRA_KEYS, mShowExtraKeys).apply();
        return mShowExtraKeys;
    }

    public boolean isBellIgnored() {
        return mIgnoreBellCharacter;
    }

    public void setIgnoreBellCharacter(Context context, boolean newValue) {
        mIgnoreBellCharacter = newValue;
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PREF_IGNORE_BELL, newValue).apply();
    }

    public void updateDataVersion(Context context) {
        mDataVersion = BuildConfig.VERSION_CODE;
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putInt(PREF_DATA_VERSION, mDataVersion).apply();
    }

    public int getDataVersion() {
        return mDataVersion;
    }

    public void setDefaultSshUser(Context context, String userName) {
        mDefaultSshUser = userName;
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(PREF_DEFAULT_SSH_USER, userName).apply();
    }

    public String getDefaultSshUser() {
        return mDefaultSshUser;
    }
    
    // 端口相关的getter和setter方法
    
    public int getSshPort() {
        return mSshPort;
    }
    
    public void setSshPort(Context context, int port) {
        mSshPort = port;
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putInt(PREF_SSH_PORT, port).apply();
    }
    
    public int getPort5678() {
        return mPort5678;
    }
    
    public void setPort5678(Context context, int port) {
        mPort5678 = port;
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putInt(PREF_PORT_5678, port).apply();
    }
    
    public int getPort5700() {
        return mPort5700;
    }
    
    public void setPort5700(Context context, int port) {
        mPort5700 = port;
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putInt(PREF_PORT_5700, port).apply();
    }
    
    public int getPort6379() {
        return mPort6379;
    }
    
    public void setPort6379(Context context, int port) {
        mPort6379 = port;
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putInt(PREF_PORT_6379, port).apply();
    }
    
    public int getPort9000() {
        return mPort9000;
    }
    
    public void setPort9000(Context context, int port) {
        mPort9000 = port;
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putInt(PREF_PORT_9000, port).apply();
    }
}
    