/*
 * Copyright (C) 2016 Jorge Ruesga
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ruesga.rview.fragments;

import android.databinding.DataBindingUtil;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.NavigationView.OnNavigationItemSelectedListener;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.ListPopupWindow;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import com.ruesga.rview.BaseActivity;
import com.ruesga.rview.R;
import com.ruesga.rview.adapters.SimpleDropDownAdapter;
import com.ruesga.rview.annotations.ProguardIgnored;
import com.ruesga.rview.databinding.DiffBaseChooserViewBinding;
import com.ruesga.rview.databinding.DiffViewerFragmentBinding;
import com.ruesga.rview.gerrit.model.ChangeInfo;
import com.ruesga.rview.misc.CacheHelper;
import com.ruesga.rview.misc.SerializationManager;
import com.ruesga.rview.model.Account;
import com.ruesga.rview.preferences.Constants;
import com.ruesga.rview.preferences.Preferences;
import com.ruesga.rview.widget.DiffView;
import com.ruesga.rview.widget.PagerControllerLayout.PagerControllerAdapter;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DiffViewerFragment extends Fragment {

    private static final String TAG = "DiffViewerFragment";

    private PagerControllerAdapter<String> mAdapter = new PagerControllerAdapter<String>() {

        @Override
        public FragmentManager getFragmentManager() {
            return getChildFragmentManager();
        }

        @Override
        public CharSequence getPageTitle(int position) {
            if (position < 0 || position >= getCount()) {
                return null;
            }
            return new File(mFiles.get(position)).getName();
        }

        @Override
        public String getItem(int position) {
            if (position < 0 || position >= getCount()) {
                return null;
            }
            return mFiles.get(position);
        }

        @Override
        public int getCount() {
            return mFiles.size();
        }

        @Override
        public Fragment getFragment(int position) {
            int base = 0;
            try {
                base = Integer.valueOf(mBase);
            } catch (Exception ex) {
                // Ignore
            }
            int revision = mChange.revisions.get(mRevisionId).number;

            final FileDiffViewerFragment fragment = FileDiffViewerFragment.newInstance(
                    mRevisionId, mFile, base, revision, mMode, mWrap,
                    mHighlightTabs, mHighlightTrailingWhitespaces);
            mFragment = new WeakReference<>(fragment);
            return fragment;
        }

        @Override
        public int getTarget() {
            return R.id.diff_content;
        }
    };

    private OnNavigationItemSelectedListener mOptionsItemListener
            = new OnNavigationItemSelectedListener() {
        @Override
        public boolean onNavigationItemSelected(@NonNull MenuItem item) {
            if (mFragment != null && mFragment.get() != null) {
                switch (item.getItemId()) {
                    case R.id.diff_mode_unified:
                        mMode = DiffView.UNIFIED_MODE;
                        Preferences.setAccountDiffMode(
                                getContext(), mAccount, Constants.DIFF_MODE_UNIFIED);
                        break;
                    case R.id.diff_mode_side_by_side:
                        mMode = DiffView.SIDE_BY_SIDE_MODE;
                        Preferences.setAccountDiffMode(
                                getContext(), mAccount, Constants.DIFF_MODE_SIDE_BY_SIDE);
                        break;
                    case R.id.wrap_mode_on:
                        mWrap = true;
                        Preferences.setAccountWrapMode(getContext(), mAccount, mWrap);
                        break;
                    case R.id.wrap_mode_off:
                        mWrap = false;
                        Preferences.setAccountWrapMode(getContext(), mAccount, mWrap);
                        break;
                    case R.id.highlight_tabs:
                        mHighlightTabs = !mHighlightTabs;
                        Preferences.setAccountHighlightTabs(getContext(), mAccount, mHighlightTabs);
                        break;
                    case R.id.highlight_trailing_whitespaces:
                        mHighlightTrailingWhitespaces = !mHighlightTrailingWhitespaces;
                        Preferences.setAccountHighlightTrailingWhitespaces(
                                getContext(), mAccount, mHighlightTrailingWhitespaces);
                        break;
                }
            }

            // Close the drawer and force a refresh of the UI
            ((BaseActivity) getActivity()).closeOptionsDrawer();
            forceRefresh();
            return true;
        }
    };

    @ProguardIgnored
    public static class Model {
        public String baseLeft;
        public String baseRight;
    }

    @ProguardIgnored
    @SuppressWarnings("unused")
    public static class EventHandlers {
        private final DiffViewerFragment mFragment;

        public EventHandlers(DiffViewerFragment fragment) {
            mFragment = fragment;
        }

        public void onBaseChooserPressed(View v) {
            mFragment.performShowBaseChooser(v);
        }
    }

    private DiffViewerFragmentBinding mBinding;
    private DiffBaseChooserViewBinding mBaseChooserBinding;
    private Model mModel = new Model();
    private EventHandlers mEventHandlers;

    private WeakReference<FileDiffViewerFragment> mFragment;

    private ChangeInfo mChange;
    private final List<String> mFiles = new ArrayList<>();
    private String mRevisionId;
    private String mFile;
    private String mBase;

    private final List<String> mAllRevisions = new ArrayList<>();

    private int mMode;
    private boolean mWrap;
    private boolean mHighlightTabs;
    private boolean mHighlightTrailingWhitespaces;

    private int mCurrentFile;

    private Account mAccount;

    public static DiffViewerFragment newInstance(String revisionId, String file) {
        DiffViewerFragment fragment = new DiffViewerFragment();
        Bundle arguments = new Bundle();
        arguments.putString(Constants.EXTRA_REVISION_ID, revisionId);
        arguments.putString(Constants.EXTRA_FILE, file);
        fragment.setArguments(arguments);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mEventHandlers = new EventHandlers(this);

        Bundle state = (savedInstanceState != null) ? savedInstanceState : getArguments();
        mRevisionId = state.getString(Constants.EXTRA_REVISION_ID);
        mFile = state.getString(Constants.EXTRA_FILE_ID);
        mBase = state.getString(Constants.EXTRA_BASE);

        setHasOptionsMenu(true);
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        mBinding = DataBindingUtil.inflate(
                inflater, R.layout.diff_viewer_fragment, container, false);
        return mBinding.getRoot();
    }

    @Override
    public void onActivityCreated(@Nullable Bundle state) {
        super.onActivityCreated(state);

        try {
            // Deserialize the change
            mChange = SerializationManager.getInstance().fromJson(
                    new String(CacheHelper.readAccountDiffCacheDir(
                            getContext(), CacheHelper.CACHE_CHANGE_JSON)), ChangeInfo.class);
            loadFiles();
            loadRevisions();

            // Get diff user preferences
            mAccount = Preferences.getAccount(getContext());
            String diffMode = Preferences.getAccountDiffMode(getContext(), mAccount);
            mMode = diffMode.equals(Constants.DIFF_MODE_SIDE_BY_SIDE)
                    ? DiffView.SIDE_BY_SIDE_MODE : DiffView.UNIFIED_MODE;
            mWrap = Preferences.getAccountWrapMode(getContext(), mAccount);
            mHighlightTabs = Preferences.isAccountHighlightTabs(getContext(), mAccount);
            mHighlightTrailingWhitespaces =
                    Preferences.isAccountHighlightTrailingWhitespaces(getContext(), mAccount);

            // Configure the pages adapter
            BaseActivity activity = ((BaseActivity) getActivity());
            activity.configurePages(mAdapter, position -> {
                mFile = mFiles.get(position);
                mCurrentFile = position;
            });
            activity.getContentBinding().pagerController.currentPage(mCurrentFile);

            // Configure the diff_options menu
            activity.configureOptionsTitle(getString(R.string.menu_diff_options));
            activity.configureOptionsMenu(R.menu.diff_options_menu, mOptionsItemListener);
            mBaseChooserBinding = DataBindingUtil.inflate(LayoutInflater.from(getContext()),
                    R.layout.diff_base_chooser_view, activity.getOptionsMenu(), false);
            updateModel();
            mBaseChooserBinding.setHandlers(mEventHandlers);
            activity.getOptionsMenu().addHeaderView(mBaseChooserBinding.getRoot());

        } catch (IOException ex) {
            Log.e(TAG, "Failed to load change cached data", ex);
            getActivity().finish();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mBinding != null) {
            mBinding.unbind();
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.diff_options, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_diff_options:
                openOptionsMenu();
                break;
        }
        return false;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putString(Constants.EXTRA_REVISION_ID, mRevisionId);
        outState.putString(Constants.EXTRA_FILE, mFile);
        outState.putString(Constants.EXTRA_BASE, mBase);
    }

    private void loadFiles() {
        mFiles.clear();
        // Revisions doesn't include the COMMIT_MSG
        mFiles.add(Constants.COMMIT_MESSAGE);
        mCurrentFile = 0;
        int i = 1;
        for (String file : mChange.revisions.get(mRevisionId).files.keySet()) {
            if (file.equals(mFile)) {
                mCurrentFile = i;
            }
            mFiles.add(file);
            i++;
        }
    }

    private void loadRevisions() {
        mAllRevisions.clear();
        for (String revision : mChange.revisions.keySet()) {
            mAllRevisions.add(String.valueOf(mChange.revisions.get(revision).number));
        }
        Collections.sort(mAllRevisions, (o1, o2) -> {
            int a = Integer.valueOf(o1);
            int b = Integer.valueOf(o2);
            if (a < b) {
                return -1;
            }
            if (a > b) {
                return 1;
            }
            return 0;
        });
    }

    private void openOptionsMenu() {
        Drawable checkMark = ContextCompat.getDrawable(getContext(), R.drawable.ic_check_box);
        Drawable uncheckMark = ContextCompat.getDrawable(
                getContext(), R.drawable.ic_check_box_outline);

        // Update diff_options
        BaseActivity activity =  ((BaseActivity) getActivity());
        Menu menu = activity.getOptionsMenu().getMenu();
        menu.findItem(R.id.diff_mode_side_by_side).setChecked(
                mMode == DiffView.SIDE_BY_SIDE_MODE);
        menu.findItem(R.id.diff_mode_unified).setChecked(mMode == DiffView.UNIFIED_MODE);
        menu.findItem(R.id.wrap_mode_on).setChecked(mWrap);
        menu.findItem(R.id.wrap_mode_off).setChecked(!mWrap);
        menu.findItem(R.id.highlight_tabs).setIcon(mHighlightTabs ? checkMark : uncheckMark);
        menu.findItem(R.id.highlight_trailing_whitespaces).setIcon(
                mHighlightTrailingWhitespaces ? checkMark : uncheckMark);

        // Open drawer
        activity.openOptionsDrawer();
    }

    private void updateModel() {
        mModel.baseLeft = mBase == null ? getString(R.string.options_base) : mBase;
        mModel.baseRight = String.valueOf(mChange.revisions.get(mRevisionId).number);
        mBaseChooserBinding.setModel(mModel);
    }

    private void performShowBaseChooser(View v) {
        final List<String> revisions = new ArrayList<>(mAllRevisions);
        String value;
        if (v.getId() == R.id.baseLeft) {
            value = mBase == null ? getString(R.string.options_base) : mBase;
            revisions.add(0, value);
        } else {
            value = String.valueOf(mChange.revisions.get(mRevisionId).number);
        }

        final ListPopupWindow popupWindow = new ListPopupWindow(getContext());
        SimpleDropDownAdapter adapter = new SimpleDropDownAdapter(
                getContext(), revisions, value);
        popupWindow.setAnchorView(v);
        popupWindow.setAdapter(adapter);
        popupWindow.setContentWidth(adapter.measureContentWidth());
        popupWindow.setOnItemClickListener((parent, view, position, id) -> {
            popupWindow.dismiss();
            if (v.getId() == R.id.baseLeft) {
                try {
                    mBase = String.valueOf(Integer.parseInt(revisions.get(position)));
                } catch (NumberFormatException ex) {
                    // 0 based
                    mBase = null;
                }
            } else {
                int rev = Integer.parseInt(revisions.get(position));
                for (String revision : mChange.revisions.keySet()) {
                    if (mChange.revisions.get(revision).number == rev) {
                        mRevisionId = revision;
                    }
                }
            }

            // Close the drawer
            ((BaseActivity) getActivity()).closeOptionsDrawer();

            // Refresh the view
            forceRefresh();
        });
        popupWindow.setModal(true);
        popupWindow.show();
    }

    private void forceRefresh() {
        updateModel();
        mAdapter.notifyDataSetChanged();
    }

}
