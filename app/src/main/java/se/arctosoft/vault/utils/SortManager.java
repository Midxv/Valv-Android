package se.arctosoft.vault.utils;

import android.content.Context;
import android.content.SharedPreferences;

public class SortManager {
    private static final String PREF_NAME = "FolderSortPrefs";

    // Sort Constants
    public static final int SORT_DATE_DESC = 0; // Newest First (Default)
    public static final int SORT_DATE_ASC = 1;  // Oldest First
    public static final int SORT_NAME_ASC = 2;  // A - Z
    public static final int SORT_NAME_DESC = 3; // Z - A

    public static void saveSortOrder(Context context, String folderUri, int sortMode) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putInt("sort_" + folderUri, sortMode).apply();
    }

    public static int getSortOrder(Context context, String folderUri) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getInt("sort_" + folderUri, SORT_DATE_DESC); // Defaults to Newest First
    }
}