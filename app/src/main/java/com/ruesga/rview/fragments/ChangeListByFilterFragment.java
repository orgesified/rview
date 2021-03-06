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

import android.content.Context;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import com.ruesga.rview.BaseActivity;
import com.ruesga.rview.R;
import com.ruesga.rview.gerrit.GerritApi;
import com.ruesga.rview.gerrit.filter.ChangeQuery;
import com.ruesga.rview.gerrit.model.ChangeInfo;
import com.ruesga.rview.gerrit.model.ChangeInput;
import com.ruesga.rview.gerrit.model.ChangeOptions;
import com.ruesga.rview.gerrit.model.InitialChangeStatus;
import com.ruesga.rview.misc.ActivityHelper;
import com.ruesga.rview.misc.ModelHelper;
import com.ruesga.rview.preferences.Preferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import androidx.annotation.Nullable;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import me.tatarka.rxloader2.RxLoader1;
import me.tatarka.rxloader2.RxLoaderManager;
import me.tatarka.rxloader2.RxLoaderObserver;
import me.tatarka.rxloader2.safe.SafeObservable;

public class ChangeListByFilterFragment extends ChangeListFragment
        implements NewChangeDialogFragment.OnNewChangeRequestedListener {

    private static final Pattern LIMIT_FILTER_PATTERN = Pattern.compile(".*( limit:(\\d+))");

    private static final List<ChangeOptions> OPTIONS = new ArrayList<ChangeOptions>() {{
        add(ChangeOptions.DETAILED_ACCOUNTS);
        add(ChangeOptions.LABELS);
        add(ChangeOptions.REVIEWED);
    }};

    private final RxLoaderObserver<ChangeInfo> mNewChangeObserver =
        new RxLoaderObserver<ChangeInfo>() {
            @Override
            public void onNext(ChangeInfo change) {
                mNewChangeLoader.clear();
                showProgress(false);

                ActivityHelper.openChangeDetails(getContext(), change, false, false);
            }

            @Override
            public void onError(Throwable error) {
                mNewChangeLoader.clear();
                handleException(error);
                showProgress(false);
            }

            @Override
            public void onStarted() {
                showProgress(true);
            }
        };

    private static final String EXTRA_FILTER = "filter";
    private static final String EXTRA_REVERSE = "reverse";
    public static final String EXTRA_HAS_SEARCH = "hasSearch";
    public static final String EXTRA_HAS_FAB = "hasFab";

    private RxLoader1<ChangeInput, ChangeInfo> mNewChangeLoader;

    public static ChangeListByFilterFragment newInstance(String filter) {
        return newInstance(filter, false, false, false);
    }

    public static ChangeListByFilterFragment newInstance(
            String filter, boolean reverse, boolean hasSearch, boolean hasFab) {
        ChangeListByFilterFragment fragment = new ChangeListByFilterFragment();
        Bundle arguments = new Bundle();
        arguments.putString(EXTRA_FILTER, filter);
        arguments.putBoolean(EXTRA_REVERSE, reverse);
        arguments.putBoolean(EXTRA_HAS_SEARCH, hasSearch);
        arguments.putBoolean(EXTRA_HAS_FAB, hasFab);
        fragment.setArguments(arguments);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //noinspection ConstantConditions
        setHasOptionsMenu(getArguments().getBoolean(EXTRA_HAS_SEARCH, false));
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.search, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_search) {
            // Show search fragment
            ActivityHelper.openSearchActivity(getActivity());
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    void setupLoaders(RxLoaderManager loaderManager) {
        mNewChangeLoader = loaderManager.create("new_change",
                this::performCreateNewChange, mNewChangeObserver);
    }

    @Override
    BaseActivity.OnFabPressedListener getFabPressedListener() {
        //noinspection ConstantConditions
        if (!getArguments().getBoolean(EXTRA_HAS_FAB, false)) {
            return null;
        }

        return fab -> {
            NewChangeDialogFragment fragment = NewChangeDialogFragment.newInstance(0, fab);
            fragment.show(getChildFragmentManager(), NewChangeDialogFragment.TAG);
        };
    }

    public Observable<List<ChangeInfo>> fetchChanges(Integer count, Integer start) {
        return Observable.zip(
                Observable.just(getCurrentData(start <= 0)),
                SafeObservable.fromCallable(() -> doFetchChanges(count, start)),
                Observable.just(count),
                this::combineChanges
            )
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread());
    }

    @SuppressWarnings("ConstantConditions")
    protected String getFilter() {
        return getArguments().getString(EXTRA_FILTER);
    }

    @SuppressWarnings("ConstantConditions")
    protected List<ChangeInfo> doFetchChanges(Integer count, Integer start) {
        String filter = getFilter();
        if (filter == null) {
            return new ArrayList<>();
        }

        // Extract limit filter
        int limit = count;
        Matcher m = LIMIT_FILTER_PATTERN.matcher(filter);
        if (m.find()) {
            limit = Integer.parseInt(m.group(2));
            filter = filter.substring(0, m.start(1));
            notifyNoMoreItems();
        }

        final ChangeQuery query = ChangeQuery.parse(filter);
        final Context ctx = getActivity();
        final GerritApi api = ModelHelper.getGerritApi(ctx);

        final boolean reverse = getArguments().getBoolean(EXTRA_REVERSE, false);
        if (reverse) {
            // We don't want endless scroll (since we are going to fetch all available
            // changes)
            notifyNoMoreItems();

            // Fetch all the available changes and reverse its order
            List<ChangeInfo> changes = new ArrayList<>();
            int s = 0;
            while (true) {
                List<ChangeInfo> fetched = api.getChanges(
                        query, limit, Math.max(0, s), OPTIONS).blockingFirst();
                changes.addAll(fetched);
                if (fetched.size() < limit) {
                    break;
                }
                s += limit;
            }

            // Sort by created date
            Collections.sort(changes, (c1, c2) -> c1.created.compareTo(c2.created));
            return changes;
        }

        // Normal fetch
        return api.getChanges(query, limit, Math.max(0, start), OPTIONS).blockingFirst();
    }

    @Override
    public void fetchNewItems() {
        resetScroll();

        final int count = Preferences.getAccountFetchedItems(
                getContext(), Preferences.getAccount(getContext()));
        final int start = 0;
        getChangesLoader().clear();
        getChangesLoader().restart(count, start);
    }

    @Override
    public void fetchMoreItems() {
        // Fetch more
        final int itemsToFetch = Preferences.getAccountFetchedItems(
                getContext(), Preferences.getAccount(getContext()));
        final int count = itemsToFetch + FETCHED_MORE_CHANGES_THRESHOLD;
        final int start = getCurrentData(false).size() - FETCHED_MORE_CHANGES_THRESHOLD;
        getChangesLoader().clear();
        getChangesLoader().restart(count, start);
    }

    @Override
    public void onNewChangeRequested(int requestCode, String project, String branch, String topic,
            String subject, boolean isPrivate, boolean isWorkInProgress) {
        ChangeInput input = new ChangeInput();
        input.project = project;
        input.branch = branch;
        input.topic = topic;
        input.subject = subject;
        if (ModelHelper.isEqualsOrGreaterVersionThan(getContext(), 2.15d)) {
            input.status = InitialChangeStatus.NEW;
            input.isPrivate = isPrivate;
            input.workInProgress = isWorkInProgress;
        } else {
            input.status = InitialChangeStatus.DRAFT;
        }

        mNewChangeLoader.clear();
        mNewChangeLoader.restart(input);
    }

    @SuppressWarnings("ConstantConditions")
    private Observable<ChangeInfo> performCreateNewChange(final ChangeInput input) {
        final Context ctx = getActivity();
        final GerritApi api = ModelHelper.getGerritApi(ctx);
        return SafeObservable.fromNullCallable(() -> api.createChange(input).blockingFirst())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }
}
