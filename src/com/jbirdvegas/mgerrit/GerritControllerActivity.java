package com.jbirdvegas.mgerrit;

/*
 * Copyright (C) 2013 Android Open Kang Project (AOKP)
 *  Author: Jon Stanford (JBirdVegas), 2013
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

import android.app.ActionBar;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentStatePagerAdapter;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.view.ViewPager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import com.jbirdvegas.mgerrit.helpers.GerritTeamsHelper;
import com.jbirdvegas.mgerrit.listeners.DefaultGerritReceivers;
import com.jbirdvegas.mgerrit.listeners.MyTabListener;
import com.jbirdvegas.mgerrit.message.*;
import com.jbirdvegas.mgerrit.objects.CommitterObject;
import com.jbirdvegas.mgerrit.objects.GerritURL;
import com.jbirdvegas.mgerrit.objects.GooFileObject;
import com.jbirdvegas.mgerrit.tasks.GerritTask;
import com.jbirdvegas.mgerrit.widgets.AddTeamView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public class GerritControllerActivity extends FragmentActivity {

    private static final String TAG = GerritControllerActivity.class.getSimpleName();
    private static final String GERRIT_INSTANCE = "gerrit";

    private CommitterObject mCommitterObject;
    private String mGerritWebsite;
    private GooFileObject mChangeLogStart;
    private GooFileObject mChangeLogStop;

    /**
     * Keep track of all the GerritTask instances so the dialog can be dismissed
     *  when this activity is paused.
     */
    private Set<GerritTask> mGerritTasks;

    SharedPreferences mPrefs;

    BroadcastReceiver mListener;

    private DefaultGerritReceivers receivers;

    // This is maintained for checking if the project has changed without looking
    private String mCurrentProject;
    private Menu mMenu;

    // Indicates if we are running this in tablet mode.
    private boolean mTwoPane;
    private ChangeListFragment mChangeList;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // check if caller has a gerrit instance start screen preference
        String suppliedGerritInstance = getIntent().getStringExtra(GERRIT_INSTANCE);
        if (suppliedGerritInstance != null
                && !suppliedGerritInstance.isEmpty()
                && suppliedGerritInstance.contains("http")) {
            // just set the prefs and allow normal loading
            Prefs.setCurrentGerrit(this, suppliedGerritInstance);
        }

        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.main);

        if (findViewById(R.id.change_detail_fragment) != null) {
            // The detail container view will be present only in the
            // large-screen layouts (res/values-large and
            // res/values-sw600dp). If this view is present, then the
            // activity should be in two-pane mode.
            mTwoPane = true;

            // TODO: In two-pane mode, list items should be given the 'activated' state when touched.
        }

        FragmentManager fm = getSupportFragmentManager();
        mChangeList = (ChangeListFragment) fm.findFragmentById(R.id.change_list_fragment);

        if (!CardsFragment.mSkipStalking) {
            try {
                mCommitterObject = getIntent()
                        .getExtras()
                        .getParcelable(CardsFragment.KEY_DEVELOPER);
            } catch (NullPointerException npe) {
                // non author specific view
                // use default
            }
        }

        // ensure we are not tracking a project unintentionally
        if ("".equals(Prefs.getCurrentProject(this))) {
            Prefs.setCurrentProject(this, null);
        }
        mCurrentProject = Prefs.getCurrentProject(this);

        try {
            mChangeLogStart = getIntent()
                    .getExtras()
                    .getParcelable(AOKPChangelog.KEY_CHANGELOG_START);
            mChangeLogStop = getIntent()
                    .getExtras()
                    .getParcelable(AOKPChangelog.KEY_CHANGELOG_STOP);
        } catch (NullPointerException npe) {
            Log.d(TAG, "Changelog was null");
        }

        mGerritWebsite = Prefs.getCurrentGerrit(this);
        mGerritTasks = new HashSet<GerritTask>();

        mPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        mListener = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String key = intent.getStringExtra(TheApplication.PREF_CHANGE_KEY);
                if (key.equals(Prefs.GERRIT_KEY))
                    onGerritChanged(Prefs.getCurrentGerrit(GerritControllerActivity.this));
                else if (key.equals(Prefs.CURRENT_PROJECT))
                    onProjectChanged(Prefs.getCurrentProject(GerritControllerActivity.this));
            }
        };
        // Don't register listener here. It is registered in onResume instead.

        /* Initially set the current Gerrit globally here.
         *  We can rely on callbacks to know when they change */
        GerritURL.setGerrit(Prefs.getCurrentGerrit(this));
        GerritURL.setProject(Prefs.getCurrentProject(this));

        receivers = new DefaultGerritReceivers(this);
    }

    // Register to receive messages.
    private void registerReceivers() {
        receivers.registerReceivers(EstablishingConnection.TYPE,
                ConnectionEstablished.TYPE,
                InitializingDataTransfer.TYPE,
                ProgressUpdate.TYPE,
                Finished.TYPE,
                HandshakeError.TYPE,
                ErrorDuringConnection.TYPE);
        LocalBroadcastManager.getInstance(this).registerReceiver(mListener,
                new IntentFilter(TheApplication.PREF_CHANGE_TYPE));
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        hideChangelogOption(Prefs.getCurrentGerrit(this));
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.gerrit_instances_menu, menu);
        this.mMenu = menu;
        return true;
    }

    private AlertDialog alertDialog = null;
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        Intent intent;
        switch (item.getItemId()) {
            case R.id.menu_save:
                intent = new Intent(this, PrefsActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
                startActivity(intent);
                return true;
            case R.id.menu_help:
                Builder builder = new Builder(this);
                builder.setTitle(R.string.menu_help);
                LayoutInflater layoutInflater = this.getLayoutInflater();
                View dialog = layoutInflater.inflate(R.layout.dialog_help, null);
                builder.setView(dialog);
                builder.create();
                builder.show();
                return true;
            case R.id.menu_refresh:
                refreshTabs();
                return true;
            case R.id.menu_team_instance:
                showGerritDialog();
                return true;
            case R.id.menu_projects:
                intent = new Intent(this, ProjectsList.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
                startActivity(intent);
                return true;
            case R.id.menu_changelog:
                Intent changelog = new Intent(this,
                        AOKPChangelog.class);
                changelog.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(changelog);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void showGerritDialog() {
        final Builder teamBuilder = new Builder(this);
        ListView instances = new ListView(this);
        Resources res = getResources();

        final ArrayList <String> teams = new ArrayList<String>(0);
        String[] gerritNames = res.getStringArray(R.array.gerrit_names);
        Collections.addAll(teams, gerritNames);

        final ArrayList<String> urls = new ArrayList<String>(0);
        String[] gerritWeb = res.getStringArray(R.array.gerrit_webaddresses);
        Collections.addAll(urls, gerritWeb);

        GerritTeamsHelper teamsHelper = new GerritTeamsHelper();
        teams.addAll(teamsHelper.getGerritNamesList());
        urls.addAll(teamsHelper.getGerritUrlsList());

        final ArrayAdapter <String> instanceAdapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_list_item_1,
                teams);
        instances.setAdapter(instanceAdapter);
        instances.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                String gerritInstanceUrl = null;
                String[] gerritInstances = getResources().getStringArray(R.array.gerrit_webaddresses);
                try {
                    gerritInstanceUrl = gerritInstances[i];
                } catch (ArrayIndexOutOfBoundsException ignored) {
                    GerritTeamsHelper helper = new GerritTeamsHelper();
                    int length = gerritInstances.length;
                    gerritInstanceUrl = helper.getGerritUrlsList().get(length - i);
                }
                Prefs.setCurrentGerrit(view.getContext(), gerritInstanceUrl);
                refreshTabs();
                if (alertDialog != null) {
                    alertDialog.dismiss();
                    alertDialog = null;
                }
            }
        });
        instances.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> adapterView, View view, int i, long l) {
                // on long click delete the file and refresh the list
                File target = new File(GerritTeamsHelper.mExternalCacheDir + "/" + teams.get(i));
                boolean success = target.delete();
                Log.v(TAG, "Attempt to delete: " + target.getAbsolutePath()
                        + " was " + success);
                if (!success) {
                    Log.v(TAG, "Files present:" + Arrays.toString(GerritTeamsHelper.mExternalCacheDir.list()));
                }
                teams.remove(i);
                urls.remove(i);
                instanceAdapter.notifyDataSetChanged();
                return success;
            }
        });
        teamBuilder.setView(instances);
        teamBuilder.setNegativeButton(R.string.cancel,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        dialogInterface.dismiss();
                    }
                });
        teamBuilder.setPositiveButton(R.string.add_gerrit_team,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        AlertDialog addTeamDialog = new Builder(teamBuilder.getContext())
                                .setTitle(R.string.add_gerrit_team)
                                .setIcon(android.R.drawable.ic_input_add)
                                .create();
                        AddTeamView.RefreshCallback callback =
                                new AddTeamView.RefreshCallback() {
                                    @Override
                                    public void refreshScreenCallback() {
                                        refreshTabs();
                                    }
                                };
                        AddTeamView addTeamView = new AddTeamView(
                                teamBuilder.getContext(),
                                addTeamDialog);
                        addTeamView.addRefreshScreenCallback(callback);
                        addTeamDialog.setView(addTeamView.getView());
                        addTeamDialog.show();
                    }
                });
        this.alertDialog = teamBuilder.create();
        this.alertDialog.show();
    }

    public void onGerritChanged(String newGerrit) {
        mGerritWebsite = newGerrit;
        Toast.makeText(this,
                new StringBuilder(0)
                        .append(getString(R.string.using_gerrit_toast))
                        .append(' ')
                        .append(newGerrit)
                        .toString(),
                Toast.LENGTH_LONG).show();
        refreshTabs();
    }

    public void onProjectChanged(String newProject) {
        mCurrentProject = newProject;
        GerritURL.setProject(newProject);
        CardsFragment.inProject = (newProject != null && newProject != "");
        refreshTabs();
    }

    /* Mark all of the tabs as dirty to trigger a refresh when they are next
     *  resumed. refresh must be called on the current fragment as it is already
     *  resumed.
     */
    public void refreshTabs() {
        mChangeList.refreshTabs();
    }

    public CommitterObject getCommitterObject() { return mCommitterObject; }
    public void clearCommitterObject() { mCommitterObject = null; }

    @Override
    protected void onPause() {
        super.onPause();
        receivers.unregisterReceivers();

        LocalBroadcastManager.getInstance(this).unregisterReceiver(mListener);

        Iterator<GerritTask> it = mGerritTasks.iterator();
        while (it.hasNext())
        {
            GerritTask gerritTask = it.next();
            if (gerritTask.getStatus() == AsyncTask.Status.FINISHED)
                it.remove();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceivers();

        // Manually check if the project changed (e.g. we are resuming from the Projects List)
        String s = Prefs.getCurrentProject(this);
        if (!s.equals(mCurrentProject)) onProjectChanged(s);

        // Manually check if the Gerrit source changed (from the Preferences)
        s = Prefs.getCurrentGerrit(this);
        if (!s.equals(mGerritWebsite)) onGerritChanged(s);
    }

    @Override
    protected void onDestroy()
    {
        super.onDestroy();

        for (GerritTask gerritTask : mGerritTasks) gerritTask.cancel(true);
        mGerritTasks.clear();
        mGerritTasks = null;
    }

    // Hide the AOKP Changelog menu option when AOKP's Gerrit is not selected
    private void hideChangelogOption(String gerrit) {
        MenuItem changelog = mMenu.findItem(R.id.menu_changelog);
        if (gerrit.contains("aokp")) {
            changelog.setVisible(true);
        } else {
            changelog.setVisible(false);
        }
    }

    /**
     * Handler for when a change is selected in the list.
     * @param changeID The currently selected change ID
     * @param expand Whether to expand the change and view the change details.
     *               Relevant only to the tablet layout.
     */
    public void onChangeSelected(String changeID, String status, boolean expand) {
        Bundle arguments = new Bundle();
        arguments.putString(PatchSetViewerFragment.CHANGE_ID, changeID);
        arguments.putString(PatchSetViewerFragment.STATUS, status);

        if (mTwoPane) {
            // In two-pane mode, show the detail view in this activity by
            // adding or replacing the detail fragment using a
            // fragment transaction.
            PatchSetViewerFragment fragment = new PatchSetViewerFragment();
            fragment.setArguments(arguments);
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.change_detail_fragment, fragment)
                    .commit();
        } else if (expand) {
            // In single-pane mode, simply start the detail activity
            // for the selected item ID.
            Intent detailIntent = new Intent(this, PatchSetViewerActivity.class);
            detailIntent.putExtras(arguments);
            startActivity(detailIntent);
        }
    }
}
