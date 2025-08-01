/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.providers.media.photopicker;

import static android.content.Intent.ACTION_GET_CONTENT;
import static android.provider.MediaStore.ACTION_PICK_IMAGES;
import static android.provider.MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP;
import static android.provider.MediaStore.grantMediaReadForPackage;

import static com.android.providers.media.photopicker.PhotoPickerSettingsActivity.EXTRA_CURRENT_USER_ID;
import static com.android.providers.media.photopicker.PickerSyncController.LOCAL_PICKER_PROVIDER_AUTHORITY;
import static com.android.providers.media.photopicker.data.PickerResult.getPickerResponseIntent;
import static com.android.providers.media.photopicker.data.PickerResult.getPickerUrisForItems;
import static com.android.providers.media.photopicker.util.LayoutModeUtils.MODE_PHOTOS_TAB;

import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.util.Log;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup.MarginLayoutParams;
import android.view.ViewOutlineProvider;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.android.modules.utils.build.SdkLevel;
import com.android.providers.media.ConfigStore;
import com.android.providers.media.R;
import com.android.providers.media.photopicker.data.PickerResult;
import com.android.providers.media.photopicker.data.Selection;
import com.android.providers.media.photopicker.data.UserIdManager;
import com.android.providers.media.photopicker.data.UserManagerState;
import com.android.providers.media.photopicker.data.model.Item;
import com.android.providers.media.photopicker.data.model.UserId;
import com.android.providers.media.photopicker.ui.TabContainerFragment;
import com.android.providers.media.photopicker.util.AccentColorResources;
import com.android.providers.media.photopicker.util.LayoutModeUtils;
import com.android.providers.media.photopicker.util.MimeFilterUtils;
import com.android.providers.media.photopicker.util.RecentsPreviewUtil;
import com.android.providers.media.photopicker.viewmodel.PickerViewModel;
import com.android.providers.media.util.ForegroundThread;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback;
import com.google.android.material.tabs.TabLayout;
import com.google.common.collect.Lists;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Photo Picker allows users to choose one or more photos and/or videos to share with an app. The
 * app does not get access to all photos/videos.
 */
public class PhotoPickerActivity extends AppCompatActivity {
    private static final String TAG =  "PhotoPickerActivity";
    private static final float BOTTOM_SHEET_PEEK_HEIGHT_PERCENTAGE = 0.60f;
    private static final float HIDE_PROFILE_BUTTON_THRESHOLD = -0.5f;
    private static final String LOGGER_INSTANCE_ID_ARG = "loggerInstanceIdArg";
    private static final String EXTRA_PRELOAD_SELECTED =
            "com.android.providers.media.photopicker.extra.PRELOAD_SELECTED";
    private ViewModelProvider mViewModelProvider;
    private PickerViewModel mPickerViewModel;
    private PreloaderInstanceHolder mPreloaderInstanceHolder;

    private Selection mSelection;
    private BottomSheetBehavior mBottomSheetBehavior;
    private View mBottomBar;
    private View mBottomSheetView;
    private View mFragmentContainerView;
    private View mDragBar;
    private View mProfileButton;
    private View mProfileMenuButton;
    private TextView mPrivacyText;
    private TabLayout mTabLayout;
    private Toolbar mToolbar;
    private CrossProfileListeners mCrossProfileListeners;
    private ConfigStore mConfigStore;
    private boolean mIsPostResumeCallFinished = true;
    private UserId mTurnedOffProfileUserId = null;
    private UserId mRemovedProfileUserId = null;


    @NonNull
    private final MutableLiveData<Boolean> mIsItemPhotoGridViewChanged =
            new MutableLiveData<>(false);

    @ColorInt
    private int mDefaultBackgroundColor;

    @ColorInt
    private int mToolBarIconColor;

    private int mToolbarHeight = 0;
    private boolean mShouldLogCancelledResult = true;

    private AccessibilityManager mAccessibilityManager;
    private boolean mIsAccessibilityEnabled;
    private boolean mIsCustomPickerColorSet = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // This is required as GET_CONTENT with type "*/*" is also received by PhotoPicker due
        // to higher priority than DocumentsUi. "*/*" mime type filter is caught as it is a superset
        // of "image/*" and "video/*".
        if (rerouteGetContentRequestIfRequired()) {
            // This activity is finishing now: we should not run the setup below,
            // BUT before we return we have to call super.onCreate() (otherwise we are we will get
            // SuperNotCalledException: Activity did not call through to super.onCreate())
            super.onCreate(savedInstanceState);
            return;
        }

        // We use the device default theme as the base theme. Apply the material them for the
        // material components. We use force "false" here, only values that are not already defined
        // in the base theme will be copied.
        getTheme().applyStyle(R.style.PickerMaterialTheme, /* force */ false);

        EdgeToEdge.enable(this);

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_photo_picker);
        mToolbar = findViewById(R.id.toolbar);
        setSupportActionBar(mToolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        View photo_picker_base = findViewById(R.id.photo_picker_base);
        ViewCompat.setOnApplyWindowInsetsListener(photo_picker_base, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            MarginLayoutParams mlp = (MarginLayoutParams) v.getLayoutParams();
            mlp.topMargin = insets.top;
            mlp.bottomMargin = insets.bottom;
            v.setLayoutParams(mlp);

            return WindowInsetsCompat.CONSUMED;
        });

        final int[] attrs = new int[]{R.attr.actionBarSize, R.attr.pickerTextColor};
        final TypedArray ta = obtainStyledAttributes(attrs);
        // Save toolbar height so that we can use it as padding for FragmentContainerView
        mToolbarHeight = ta.getDimensionPixelSize(/* index */ 0, /* defValue */ -1);
        mToolBarIconColor = ta.getColor(/* index */ 1,/* defValue */ -1);
        ta.recycle();

        mDefaultBackgroundColor = getColor(R.color.picker_background_color);

        mViewModelProvider = new ViewModelProvider(this);
        mPickerViewModel = getOrCreateViewModel();
        mConfigStore = mPickerViewModel.getConfigStore();

        final Intent intent = getIntent();
        try {
            mPickerViewModel.parseValuesFromIntent(intent);
            mIsCustomPickerColorSet =
                    mPickerViewModel.getPickerAccentColorParameters().isCustomPickerColorSet();

            // This needs to happen after we have parsed values from Intent.
            mPickerViewModel.maybeInitPhotoPickerData();

            if (mIsCustomPickerColorSet) {
                mDefaultBackgroundColor =
                        mPickerViewModel.getPickerAccentColorParameters().getThemeBasedColor(
                                AccentColorResources.SURFACE_CONTAINER_COLOR_LIGHT,
                                AccentColorResources.SURFACE_CONTAINER_COLOR_DARK
                );
                mToolBarIconColor =
                        mPickerViewModel.getPickerAccentColorParameters().getThemeBasedColor(
                                AccentColorResources.ON_SURFACE_COLOR_LIGHT,
                                AccentColorResources.ON_SURFACE_COLOR_DARK
                );
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Finish activity due to an exception while parsing extras", e);
            finishWithoutLoggingCancelledResult();
            return;
        }
        mSelection = mPickerViewModel.getSelection();
        mDragBar = findViewById(R.id.drag_bar);
        mPrivacyText = findViewById(R.id.privacy_text);
        mBottomBar = findViewById(R.id.picker_bottom_bar);
        mProfileButton = findViewById(R.id.profile_button);
        mProfileMenuButton = findViewById(R.id.profile_menu_button);
        mTabLayout = findViewById(R.id.tab_layout);

        mAccessibilityManager = getSystemService(AccessibilityManager.class);
        mIsAccessibilityEnabled = mAccessibilityManager.isEnabled();

        initBottomSheetBehavior();

        // Save the fragment container layout so that we can adjust the padding based on preview or
        // non-preview mode.
        mFragmentContainerView = findViewById(R.id.fragment_container);

        mCrossProfileListeners = new CrossProfileListeners();

        mPreloaderInstanceHolder = mViewModelProvider.get(PreloaderInstanceHolder.class);
        if (mPreloaderInstanceHolder.preloader != null) {
            subscribeToSelectedMediaPreloader(mPreloaderInstanceHolder.preloader);
        }

        observeRefreshUiNotificationLiveData();

        if (SdkLevel.isAtLeastV()) {
            updateRecentsScreenshotSetting();
        }

        // Restore state operation should always be kept at the end of this method.
        restoreState(savedInstanceState);

        // Call this after state is restored, to use the correct LOGGER_INSTANCE_ID_ARG
        if (savedInstanceState == null) {
            final String intentAction = intent != null ? intent.getAction() : null;
            mPickerViewModel.logPickerOpened(getCallingUid(), getCallingPackage(), intentAction);
        }
    }

    private int getCallingUid() {
        final String callingPackage = getCallingPackage();
        try {
            return getPackageManager().getPackageUid(callingPackage, /* flags= */ 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.d(TAG, "Returning calling uid as -1; callingPackage: " + callingPackage + ".", e);
            return -1;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mCrossProfileListeners != null) {
            // This is required to unregister any broadcast receivers.
            mCrossProfileListeners.onDestroy();
        }
    }

    /**
     * Gets PickerViewModel instance populated with the current calling package's uid.
     *
     * This method is also needed for tests, allowing ourselves to control ViewModel creation
     * helps us mock the ViewModel for test.
     */
    @VisibleForTesting
    @NonNull
    protected PickerViewModel getOrCreateViewModel() {
        PickerViewModel viewModel =  mViewModelProvider.get(PickerViewModel.class);

        // init user managers.
        final UserIdManager userIdManager = getUserIdManager();
        viewModel.initUserManagers(userIdManager);

        // populate calling package UID in PickerViewModel instance.
        try {
            if (getCallingPackage() != null) {
                viewModel.setCallingPackageUid(
                        getPackageManager().getPackageUid(getCallingPackage(), 0));
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // no-op since the default value is -1.
        }
        return viewModel;
    }

    /**
     * @return {@link UserIdManager} to be used for the {@link PickerViewModel} when calling
     * {@link PickerViewModel#initUserManagers(UserIdManager)}.
     *
     * <p> This method is also needed for tests; Allowing ourselves to control UserIdManager
     * creation helps us mock the UserIdManager for tests. </p>
     */
    @VisibleForTesting
    @NonNull
    protected UserIdManager getUserIdManager() {
        return UserIdManager.create(getApplicationContext());
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event){
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            if (mBottomSheetBehavior.getState() == BottomSheetBehavior.STATE_COLLAPSED) {

                Rect outRect = new Rect();
                mBottomSheetView.getGlobalVisibleRect(outRect);

                if (!outRect.contains((int)event.getRawX(), (int)event.getRawY())) {
                    mBottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
                }
            }
        }
        return super.dispatchTouchEvent(event);
    }

    /**
     * This method is called on action bar home button clicks if
     * {@link androidx.appcompat.app.ActionBar#setDisplayHomeAsUpEnabled(boolean)} is set
     * {@code true}.
     */
    @Override
    public boolean onSupportNavigateUp() {
        int backStackEntryCount = getSupportFragmentManager().getBackStackEntryCount();
        mPickerViewModel.logActionBarHomeButtonClick(backStackEntryCount);
        super.onBackPressed();
        return true;
    }

    @Override
    public void onBackPressed() {
        int backStackEntryCount = getSupportFragmentManager().getBackStackEntryCount();
        mPickerViewModel.logBackGestureWithStackCount(backStackEntryCount);
        super.onBackPressed();
    }

    @Override
    public boolean onMenuOpened(int featureId, Menu menu) {
        mPickerViewModel.logMenuOpened();
        return super.onMenuOpened(featureId, menu);
    }

    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(title);
        getSupportActionBar().setTitle(title);
    }

    /**
     * Called when owning activity is saving state to be used to restore state during creation.
     *
     * @param state Bundle to save state
     */
    @Override
    public void onSaveInstanceState(Bundle state) {
        // The below assignment is necessary to track activity resume status, when PhotoPicker is
        // opened in the background and any profile that is currently selected in PhotoPicker is
        // turned-off or removed by the user.
        // So if mIsPostResumeCallFinished is false means currently we can not refresh UI until
        // the activity is fully resumed (Until mIsPostResumeCallFinished is assigned as true
        // in onPostResume()) to avoid fragment state loss in onSaveInstanceState.
        mIsPostResumeCallFinished = false;
        super.onSaveInstanceState(state);
        saveBottomSheetState();
        state.putParcelable(LOGGER_INSTANCE_ID_ARG, mPickerViewModel.getInstanceId());
    }

    @Override
    public void onPostResume() {
        super.onPostResume();
        // The below task will handle turned-off/removed request,  when PhotoPicker is
        // opened in the background and any profile is turned-off or removed by the user.
        // when activity is not fully resumed and a profile is requested to be turned-off or removed
        // in between, we will handle those requests in onPostResume to avoid fragment state
        // loss in onSaveInstanceState.
        new Handler().post(() -> {
            mCrossProfileListeners.handleTurnedOffOrRemovedProfile();
            mIsPostResumeCallFinished = true;
        });
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        getMenuInflater().inflate(R.menu.picker_overflow_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(@NonNull Menu menu) {
        super.onPrepareOptionsMenu(menu);
        // All logic to hide/show an item in the menu must be in this method
        final MenuItem settingsMenuItem = menu.findItem(R.id.settings);

        // TODO(b/195009187): Settings menu item is hidden by default till Settings page is
        // completely developed.
        settingsMenuItem.setVisible(shouldShowSettingsScreen());

        // Browse menu item allows users to launch DocumentsUI. This item should only be shown if
        // PhotoPicker was opened via {@link #ACTION_GET_CONTENT}.
        menu.findItem(R.id.browse).setVisible(isGetContentAction());

        return menu.hasVisibleItems();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.browse) {
            mPickerViewModel.logBrowseToDocumentsUi(Binder.getCallingUid(),
                    getCallingPackage());
            launchDocumentsUiAndFinishPicker();
            return true;
        } else if (itemId == R.id.settings) {
            startSettingsActivity();
            return true;
        }
        // Continue to return the result of base class' onOptionsItemSelected(item)
        return super.onOptionsItemSelected(item);
    }

    /**
     * Launch the Photo Picker settings page where user can view/edit current cloud media provider.
     */
    public void startSettingsActivity() {
        final Intent intent = new Intent(this, PhotoPickerSettingsActivity.class);
        intent.putExtra(EXTRA_CURRENT_USER_ID, getCurrentUserId());
        startActivity(intent);
    }

    /**
     * @return {@code true} if the intent was re-routed to the DocumentsUI (and this
     *  {@code PhotoPickerActivity} is {@link #isFinishing()} now). {@code false} - otherwise.
     */
    private boolean rerouteGetContentRequestIfRequired() {
        final Intent intent = getIntent();
        if (!ACTION_GET_CONTENT.equals(intent.getAction())) {
            return false;
        }

        // TODO(b/232775643): Workaround to support PhotoPicker invoked from DocumentsUi.
        // GET_CONTENT for all (media and non-media) files opens DocumentsUi, but it still shows
        // "Photo Picker app option. When the user clicks on "Photo Picker", the same intent which
        // includes filters to show non-media files as well is forwarded to PhotoPicker.
        // Make sure Photo Picker is opened when the intent is explicitly forwarded by documentsUi
        if (isIntentReferredByDocumentsUi(getReferrer())) {
            Log.i(TAG, "Open PhotoPicker when a forwarded ACTION_GET_CONTENT intent is received");
            return false;
        }

        // Check if we can handle the specified MIME types.
        // If we can - do not reroute and thus return false.
        if (!MimeFilterUtils.requiresUnsupportedFilters(intent)) return false;

        launchDocumentsUiAndFinishPicker();
        return true;
    }

    private boolean isIntentReferredByDocumentsUi(Uri referrerAppUri) {
        ComponentName documentsUiComponentName = getDocumentsUiComponentName(this);
        String documentsUiPackageName = documentsUiComponentName != null
                ? documentsUiComponentName.getPackageName() : null;
        return referrerAppUri != null && referrerAppUri.getHost().equals(documentsUiPackageName);
    }

    private void launchDocumentsUiAndFinishPicker() {
        Log.i(TAG, "Launch DocumentsUI and finish picker");

        startActivityAsUser(getDocumentsUiForwardingIntent(this, getIntent()),
                UserId.CURRENT_USER.getUserHandle());
        // RESULT_CANCELLED is not returned to the calling app as the DocumentsUi result will be
        // returned. We don't have to log as this flow can be called in 2 cases:
        // 1. GET_CONTENT had non-media filters, so the user or the app should be unaffected as they
        // see that DocumentsUi was opened directly.
        // 2. User clicked on "Browse.." button, in that case we already log that event separately.
        finishWithoutLoggingCancelledResult();
    }

    @VisibleForTesting
    static Intent getDocumentsUiForwardingIntent(Context context, Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
        intent.addFlags(Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP);
        intent.setComponent(getDocumentsUiComponentName(context));
        return intent;
    }

    private static ComponentName getDocumentsUiComponentName(Context context) {
        final PackageManager pm = context.getPackageManager();
        // DocumentsUI is the default handler for ACTION_OPEN_DOCUMENT
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        return intent.resolveActivity(pm);
    }

    private void restoreState(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            restoreBottomSheetState();
            mPickerViewModel.setInstanceId(
                    savedInstanceState.getParcelable(LOGGER_INSTANCE_ID_ARG));
        } else {
            setupInitialLaunchState();
        }
    }

    /**
     * Sets up states for the initial launch. This includes updating common layouts, selecting
     * Photos tab item and saving the current bottom sheet state for later.
     */
    private void setupInitialLaunchState() {
        updateCommonLayouts(MODE_PHOTOS_TAB, /* title */ "");
        TabContainerFragment.show(getSupportFragmentManager());
        saveBottomSheetState();
    }

    private void initBottomSheetBehavior() {
        mBottomSheetView = findViewById(R.id.bottom_sheet);
        mBottomSheetBehavior = BottomSheetBehavior.from(mBottomSheetView);
        initStateForBottomSheet();

        mBottomSheetBehavior.addBottomSheetCallback(createBottomSheetCallBack());
        setRoundedCornersForBottomSheet();
        if (mIsCustomPickerColorSet) {
            setCustomPickerColorsInBottomSheet(
                    mPickerViewModel.getPickerAccentColorParameters().getThemeBasedColor(
                            AccentColorResources.SURFACE_CONTAINER_COLOR_LIGHT,
                            AccentColorResources.SURFACE_CONTAINER_COLOR_DARK),
                    mPickerViewModel.getPickerAccentColorParameters().getThemeBasedColor(
                            AccentColorResources.SURFACE_CONTAINER_HIGHEST_LIGHT,
                            AccentColorResources.SURFACE_CONTAINER_HIGHEST_DARK)
            );
        }
    }

    private void setCustomPickerColorsInBottomSheet(int backgroundColor, int dragBarColor) {
        mBottomSheetView.setBackgroundColor(backgroundColor);
        ImageView dragBarImageView = findViewById(R.id.drag_bar);
        GradientDrawable dragBarDrawable = (GradientDrawable) dragBarImageView.getDrawable();
        dragBarDrawable.setColor(dragBarColor);
    }

    private BottomSheetCallback createBottomSheetCallBack() {
        return new BottomSheetCallback() {
            private boolean mIsProfileButtonHiddenDueToBottomSheetClosing = false;
            private boolean mIsProfileMenuButtonHiddenDueToBottomSheetClosing = false;
            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    mPickerViewModel.logSwipeDownExit();
                    finish();
                } else if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    mPickerViewModel.logExpandToFullScreen();
                }
                saveBottomSheetState();
            }

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                // slideOffset = -1 is when bottomsheet is completely hidden
                // slideOffset = 0 is when bottomsheet is in collapsed mode
                // slideOffset = 1 is when bottomsheet is in expanded mode
                // We hide the Profile button if the bottomsheet is 50% in between collapsed state
                // and hidden state.
                onSlideProfileButton(slideOffset);
                onSlideProfileMenuButton(slideOffset);

            }

            void onSlideProfileButton(float slideOffset) {
                // We hide the Profile button if the bottomsheet is 50% in between collapsed state
                // and hidden state.
                if (slideOffset < HIDE_PROFILE_BUTTON_THRESHOLD
                        && mProfileButton.getVisibility() == View.VISIBLE) {
                    mProfileButton.setVisibility(View.GONE);
                    mIsProfileButtonHiddenDueToBottomSheetClosing = true;
                    return;
                }

                // We need to handle this state if the user is swiping till the bottom of the
                // screen but then swipes up bottom sheet suddenly
                if (slideOffset > HIDE_PROFILE_BUTTON_THRESHOLD
                        && mIsProfileButtonHiddenDueToBottomSheetClosing) {
                    mProfileButton.setVisibility(View.VISIBLE);
                    mIsProfileButtonHiddenDueToBottomSheetClosing = false;
                }
            }

            void onSlideProfileMenuButton(float slideOffset) {
                if (!(mConfigStore.isPrivateSpaceInPhotoPickerEnabled() && SdkLevel.isAtLeastS())) {
                    return;
                }
                if (slideOffset < HIDE_PROFILE_BUTTON_THRESHOLD
                        && mProfileMenuButton.getVisibility() == View.VISIBLE) {
                    mProfileMenuButton.setVisibility(View.GONE);
                    mIsProfileMenuButtonHiddenDueToBottomSheetClosing = true;
                    return;
                }

                // We need to handle this state if the user is swiping till the bottom of the
                // screen but then swipes up bottom sheet suddenly
                if (slideOffset > HIDE_PROFILE_BUTTON_THRESHOLD
                        && mIsProfileMenuButtonHiddenDueToBottomSheetClosing) {
                    mProfileMenuButton.setVisibility(View.VISIBLE);
                    mIsProfileMenuButtonHiddenDueToBottomSheetClosing = false;
                }
            }
        };
    }


    private void setRoundedCornersForBottomSheet() {
        final float cornerRadius =
                getResources().getDimensionPixelSize(R.dimen.picker_top_corner_radius);
        final ViewOutlineProvider viewOutlineProvider = new ViewOutlineProvider() {
            @Override
            public void getOutline(final View view, final Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(),
                        (int)(view.getHeight() + cornerRadius), cornerRadius);
            }
        };
        mBottomSheetView.setOutlineProvider(viewOutlineProvider);
    }

    private void initStateForBottomSheet() {
        if (!isAccessibilityEnabled() && !mSelection.canSelectMultiple()
                && !isOrientationLandscape()) {
            final int peekHeight = getBottomSheetPeekHeight(this);
            mBottomSheetBehavior.setPeekHeight(peekHeight);
            mBottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        } else {
            mBottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            mBottomSheetBehavior.setSkipCollapsed(true);
        }
    }

    /**
     * Warning: This method is visible for espresso tests, we are not customizing anything here.
     * Allowing ourselves to control the accessibility state helps us mock it for these tests.
     */
    @VisibleForTesting
    protected boolean isAccessibilityEnabled() {
        return mIsAccessibilityEnabled;
    }

    private static int getBottomSheetPeekHeight(Context context) {
        final WindowManager windowManager = context.getSystemService(WindowManager.class);
        final Rect displayBounds = windowManager.getCurrentWindowMetrics().getBounds();
        return (int) (displayBounds.height() * BOTTOM_SHEET_PEEK_HEIGHT_PERCENTAGE);
    }

    private void restoreBottomSheetState() {
        // BottomSheet is always EXPANDED for landscape
        if (isOrientationLandscape()) {
            return;
        }
        final int savedState = mPickerViewModel.getBottomSheetState();
        if (isValidBottomSheetState(savedState)) {
            mBottomSheetBehavior.setState(savedState);
        }
    }

    private void saveBottomSheetState() {
        // Do not save state for landscape or preview mode. This is because they are always in
        // STATE_EXPANDED state.
        if (isOrientationLandscape() || !mBottomSheetView.getClipToOutline()) {
            return;
        }
        mPickerViewModel.setBottomSheetState(mBottomSheetBehavior.getState());
    }

    private boolean isValidBottomSheetState(int state) {
        return state == BottomSheetBehavior.STATE_COLLAPSED ||
                state == BottomSheetBehavior.STATE_EXPANDED;
    }

    private boolean isOrientationLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    public LiveData<Boolean> isItemPhotoGridViewChanged() {
        return mIsItemPhotoGridViewChanged;
    }

    public void setResultAndFinishSelf() {
        logPickerSelectionConfirmed(mSelection.getSelectedItems().size());
        if (shouldPreloadSelectedItems()) {
            final var uris = PickerResult.getPickerUrisForItems(
                    getIntent().getAction(), mSelection.getSelectedItems());
            mPickerViewModel.logPreloadingStarted(uris.size());
            mPreloaderInstanceHolder.preloader =
                    SelectedMediaPreloader.preload(/* activity */ this, uris);
            deSelectUnavailableMedia(mPreloaderInstanceHolder.preloader);
            subscribeToSelectedMediaPreloader(mPreloaderInstanceHolder.preloader);
        } else {
            setResultAndFinishSelfInternal();
        }
    }

    private void setResultAndFinishSelfInternal() {
        // In addition to the activity result, add the selected files to the MediaProvider
        // media_grants database.
        if (isUserSelectImagesForAppAction()) {
            setResultForUserSelectImagesForAppAction();
        } else {
            setResultForPickImagesOrGetContentAction();
        }

        finishWithoutLoggingCancelledResult();
    }

    private void setResultForUserSelectImagesForAppAction() {
        // Since Photopicker is in permission mode, don't send back URI grants.
        setResult(RESULT_OK);
        // The permission controller will pass the requesting package's UID here
        final Bundle extras = getIntent().getExtras();
        final int uid = extras.getInt(Intent.EXTRA_UID);
        final List<Uri> uris = getPickerUrisForItems(getIntent().getAction(),
                mSelection.getNewlySelectedItems());
        if (!uris.isEmpty()) {
            ForegroundThread.getExecutor().execute(() -> {
                // Handle grants in another thread to not block the UI.
                grantMediaReadForPackage(getApplicationContext(), uid, uris);
                mPickerViewModel.logPickerChoiceAddedGrantsCount(uris.size(), extras);
            });
        }

        // Revoke READ_GRANT for items that were pre-granted but now in the current session user has
        // deselected them.
        if (mPickerViewModel.isManagedSelectionEnabled()) {
            final List<Uri> urisForItemsWhoseGrantsNeedsToBeRevoked = getPickerUrisForItems(
                    getIntent().getAction(), mSelection.getDeselectedItemsToBeRevoked()
                            .stream().collect(Collectors.toList()));
            if (!urisForItemsWhoseGrantsNeedsToBeRevoked.isEmpty()) {
                ForegroundThread.getExecutor().execute(() -> {
                    // Handle grants in another thread to not block the UI.
                    MediaStore.revokeMediaReadForPackages(getApplicationContext(), uid,
                            urisForItemsWhoseGrantsNeedsToBeRevoked);
                    mPickerViewModel.logPickerChoiceRevokedGrantsCount(
                            urisForItemsWhoseGrantsNeedsToBeRevoked.size(), extras);
                });
            }
        }
    }

    private void setResultForPickImagesOrGetContentAction() {
        final Intent resultData = getPickerResponseIntent(getIntent().getAction(),
                mSelection.canSelectMultiple(), mSelection.getSelectedItems());
        setResult(RESULT_OK, resultData);
    }

    /**
     * Inspects the current selection list to see if any items in the selection have an authority
     * that does not match the {@link MediaStore.AUTHORITY}
     *
     * <p>If all items have the MediaStore authority, it is presumed that the selection only
     * contains local items.
     *
     * @return Whether the selection includes only local items
     */
    private boolean isSelectionOnlyLocalItems() {

        for (Item item : mSelection.getSelectedItems()) {
            if (!item.getContentUri().getAuthority().equals(LOCAL_PICKER_PROVIDER_AUTHORITY)) {
                return false;
            }
        }
        return true;
    }

    private boolean shouldPreloadSelectedItems() {
        // Only preload if the cloud media may be shown in the PhotoPicker.
        if (isSelectionOnlyLocalItems()) {
            return false;
        }

        final boolean isGetContent = isGetContentAction();
        final boolean isPickImages = isPickImagesAction();
        final ConfigStore cs = mPickerViewModel.getConfigStore();

        if (getIntent().hasExtra(EXTRA_PRELOAD_SELECTED)) {
            if (Build.isDebuggable()
                    || (isPickImages && cs.shouldPickerRespectPreloadArgumentForPickImages())) {
                return getIntent().getBooleanExtra(EXTRA_PRELOAD_SELECTED,
                        /* default, not used */ false);
            }
        }

        if (isGetContent) {
            return cs.shouldPickerPreloadForGetContent();
        } else if (isPickImages) {
            return cs.shouldPickerPreloadForPickImages();
        } else {
            Log.w(TAG, "Not preloading selection for \"" + getIntent().getAction() + "\" action");
            return false;
        }
    }

    private void subscribeToSelectedMediaPreloader(@NonNull SelectedMediaPreloader preloader) {
        preloader.getIsFinishedLiveData().observe(
                /* lifecycleOwner */ PhotoPickerActivity.this,
                isFinished -> {
                    if (isFinished) {
                        mPickerViewModel.logPreloadingFinished();
                        setResultAndFinishSelfInternal();
                    }
                });
    }

    // This method is responsible for deselecting all  unavailable items from selection list
    // when user tries selecting unavailable could only media (not cached) while offline
    private void deSelectUnavailableMedia(@NonNull SelectedMediaPreloader preloader) {
        preloader.getUnavailableMediaIndexes().observe(
                /* lifecycleOwner */ PhotoPickerActivity.this,
                unavailableMediaIndexes -> {
                    if (unavailableMediaIndexes.size() > 0) {
                        // To notify the fragment to uncheck the unavailable items at UI those are
                        // no longer available in the selection list.
                        mIsItemPhotoGridViewChanged.postValue(true);

                        // Checking if preloading was intentionally be cancelled by the user
                        if (unavailableMediaIndexes.get(unavailableMediaIndexes.size() - 1) != -1) {
                            // Displaying  error dialog with an error message when the user tries
                            // to add unavailable cloud only media (not cached) while offline.
                            DialogUtils.showDialog(this,
                                    getResources().getString(R.string.dialog_error_title),
                                    getResources().getString(R.string.dialog_error_message));
                            mPickerViewModel.logPreloadingFailed(unavailableMediaIndexes.size());
                        } else {
                            unavailableMediaIndexes.remove(
                                    unavailableMediaIndexes.size() - 1);
                            mPickerViewModel.logPreloadingCancelled(unavailableMediaIndexes.size());
                        }
                        List<Item> selectedItems = mSelection.getSelectedItems();
                        for (var mediaIndex : unavailableMediaIndexes) {
                            mSelection.removeSelectedItem(selectedItems.get(mediaIndex));
                        }
                    }
                });
    }

    /**
     * NOTE: this may wrongly return {@code false} if called before {@link PickerViewModel} had a
     * chance to fetch the authority and the account of the current
     * {@link android.provider.CloudMediaProvider}.
     * However, this may only happen very early on in the lifecycle.
     */
    private boolean isCloudMediaAvailable() {
        return mPickerViewModel.getCloudMediaProviderAuthorityLiveData().getValue() != null
                && mPickerViewModel.getCloudMediaAccountNameLiveData().getValue() != null;
    }

    /**
     * This should be called if:
     * * We are finishing Picker explicitly before the user has seen PhotoPicker UI due to known
     *   checks/workflow.
     * * We are not returning {@link Activity#RESULT_CANCELED}
     */
    private void finishWithoutLoggingCancelledResult() {
        mShouldLogCancelledResult = false;
        finish();
    }

    @Override
    public void finish() {
        if (mShouldLogCancelledResult) {
            logPickerCancelled();
        }
        super.finish();
    }

    private void logPickerSelectionConfirmed(int countOfItemsConfirmed) {
        mPickerViewModel.logPickerConfirm(Binder.getCallingUid(), getCallingPackage(),
                countOfItemsConfirmed);
    }

    private void logPickerCancelled() {
        mPickerViewModel.logPickerCancel(Binder.getCallingUid(), getCallingPackage());
    }

    @UserIdInt
    private int getCurrentUserId() {
        if (mConfigStore.isPrivateSpaceInPhotoPickerEnabled() && SdkLevel.isAtLeastS()) {
            return mPickerViewModel.getUserManagerState().getCurrentUserProfileId().getIdentifier();
        }
        return mPickerViewModel.getUserIdManager().getCurrentUserProfileId().getIdentifier();
    }

    /**
     * Updates the common views such as Title, Toolbar, Navigation bar, status bar and bottom sheet
     * behavior
     *
     * @param mode {@link LayoutModeUtils.Mode} which describes the layout mode to update.
     * @param title the title to set for the Activity
     */
    public void updateCommonLayouts(LayoutModeUtils.Mode mode, String title) {
        updateTitle(title);
        updateToolbar(mode);
        updateStatusBarAndNavigationBar(mode);
        updateBottomSheetBehavior(mode);
        updateFragmentContainerViewPadding(mode);
        updateDragBarVisibility(mode);
        updateHeaderTextVisibility(mode);
        // The bottom bar and profile button are not shown on preview, hide them in preview. We
        // handle the visibility of them in TabFragment. We don't need to make them shown in
        // non-preview page here.
        if (mode.isPreview) {
            mBottomBar.setVisibility(View.GONE);
            mProfileButton.setVisibility(View.GONE);
            mProfileMenuButton.setVisibility(View.GONE);
        }
    }

    private void updateTitle(String title) {
        setTitle(title);
    }

    /**
     * Updates the icons and show/hide the tab layout with {@code mode}.
     *
     * @param mode {@link LayoutModeUtils.Mode} which describes the layout mode to update.
     */
    private void updateToolbar(@NonNull LayoutModeUtils.Mode mode) {
        final boolean isPreview = mode.isPreview;
        final boolean shouldShowTabLayout = mode.isPhotosTabOrAlbumsTab;
        // 1. Set the tabLayout visibility
        mTabLayout.setVisibility(shouldShowTabLayout ? View.VISIBLE : View.GONE);

        // 2. Set the toolbar color
        final ColorDrawable toolbarColor;
        if (isPreview && !shouldShowTabLayout) {
            if (isOrientationLandscape()) {
                // Toolbar in Preview will have transparent color in Landscape mode.
                toolbarColor = new ColorDrawable(getColor(android.R.color.transparent));
            } else {
                // Toolbar in Preview will have a solid color with 90% opacity in Portrait mode.
                toolbarColor = new ColorDrawable(getColor(R.color.preview_scrim_solid_color));
            }
        } else {
            toolbarColor = new ColorDrawable(mDefaultBackgroundColor);
        }
        getSupportActionBar().setBackgroundDrawable(toolbarColor);

        // 3. Set the toolbar icon.
        final Drawable icon;
        if (shouldShowTabLayout) {
            icon = getDrawable(R.drawable.ic_close);
            if (mIsCustomPickerColorSet) {
                icon.setTint(mPickerViewModel.getPickerAccentColorParameters().getThemeBasedColor(
                        AccentColorResources.ON_SURFACE_COLOR_LIGHT,
                        AccentColorResources.ON_SURFACE_COLOR_DARK
                ));
            }
        } else {
            icon = getDrawable(R.drawable.ic_arrow_back);
            // Preview mode has dark background, hence icons will be WHITE in color
            icon.setTint(isPreview ? Color.WHITE : mToolBarIconColor);
        }
        getSupportActionBar().setHomeAsUpIndicator(icon);
        getSupportActionBar().setHomeActionContentDescription(
                shouldShowTabLayout ? android.R.string.cancel
                        : R.string.abc_action_bar_up_description);
        if (mToolbar.getOverflowIcon() != null) {
            mToolbar.getOverflowIcon().setTint(isPreview ? Color.WHITE : mToolBarIconColor);
        }
    }

    /**
     * Updates status bar and navigation bar
     *
     * @param mode {@link LayoutModeUtils.Mode} which describes the layout mode to update.
     */
    private void updateStatusBarAndNavigationBar(@NonNull LayoutModeUtils.Mode mode) {
        final boolean isPreview = mode.isPreview;
        final int navigationBarColor = isPreview ? getColor(R.color.preview_background_color) :
                mDefaultBackgroundColor;
        getWindow().setNavigationBarColor(navigationBarColor);

        final int statusBarColor = isPreview ? getColor(R.color.preview_background_color) :
                getColor(android.R.color.transparent);
        getWindow().setStatusBarColor(statusBarColor);

        // Update the system bar appearance
        final int mask = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        int appearance = 0;
        if (!isPreview) {
            final int uiModeNight =
                    getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;

            if (uiModeNight == Configuration.UI_MODE_NIGHT_NO) {
                // If the system is not in Dark theme, set the system bars to light mode.
                appearance = mask;
            }
        }
        getWindow().getInsetsController().setSystemBarsAppearance(appearance, mask);
    }

    /**
     * Updates the bottom sheet behavior
     *
     * @param mode {@link LayoutModeUtils.Mode} which describes the layout mode to update.
     */
    private void updateBottomSheetBehavior(@NonNull LayoutModeUtils.Mode mode) {
        final boolean isPreview = mode.isPreview;
        if (mBottomSheetView != null) {
            mBottomSheetView.setClipToOutline(!isPreview);
            // TODO(b/197241815): Add animation downward swipe for preview should go back to
            // the photo in photos grid
            mBottomSheetBehavior.setDraggable(!isPreview);
        }
        if (isPreview) {
            if (mBottomSheetBehavior.getState() != BottomSheetBehavior.STATE_EXPANDED) {
                // Sets bottom sheet behavior state to STATE_EXPANDED if it's not already expanded.
                // This is useful when user goes to Preview mode which is always Full screen.
                // TODO(b/197241815): Add animation preview to full screen and back transition to
                // partial screen. This is similar to long press animation.
                mBottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        } else {
            restoreBottomSheetState();
        }
    }

    /**
     * Updates the FragmentContainerView padding.
     * <p>
     * For Preview mode, toolbar overlaps the Fragment content, hence the padding will be set to 0.
     * For Non-Preview mode, toolbar doesn't overlap the contents of the fragment, hence we set the
     * padding as the height of the toolbar.
     */
    private void updateFragmentContainerViewPadding(@NonNull LayoutModeUtils.Mode mode) {
        if (mFragmentContainerView == null) return;

        final int topPadding;
        if (mode.isPreview) {
            topPadding = 0;
        } else {
            topPadding = mToolbarHeight;
        }

        mFragmentContainerView.setPadding(mFragmentContainerView.getPaddingLeft(),
                topPadding, mFragmentContainerView.getPaddingRight(),
                mFragmentContainerView.getPaddingBottom());
    }

    private void updateDragBarVisibility(@NonNull LayoutModeUtils.Mode mode) {
        final boolean shouldShowDragBar = !mode.isPreview;
        mDragBar.setVisibility(shouldShowDragBar ? View.VISIBLE : View.GONE);
    }

    private void updateHeaderTextVisibility(@NonNull LayoutModeUtils.Mode mode) {
        // The privacy text is only shown on the Photos tab and Albums tab when not in
        // permission select mode.
        final boolean shouldShowPrivacyMessage = mode.isPhotosTabOrAlbumsTab;

        if (!shouldShowPrivacyMessage) {
            mPrivacyText.setVisibility(View.GONE);
            return;
        }

        if (mPickerViewModel.isUserSelectForApp()) {
            mPrivacyText.setText(R.string.picker_header_permissions);
            mPrivacyText.setTextSize(
                    TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimension(R.dimen.picker_user_select_header_text_size));
        } else {
            mPrivacyText.setText(R.string.picker_privacy_message);
            mPrivacyText.setTextSize(
                    TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimension(R.dimen.picker_privacy_text_size));
            if (mIsCustomPickerColorSet) {
                mPrivacyText.setTextColor(
                        mPickerViewModel.getPickerAccentColorParameters().getThemeBasedColor(
                                AccentColorResources.ON_SURFACE_VARIANT_LIGHT,
                                AccentColorResources.ON_SURFACE_VARIANT_DARK
                        ));
            }
        }

        mPrivacyText.setVisibility(View.VISIBLE);
    }

    /**
     * Clear all the fragments in the FragmentManager
     */
    void clearFragments() {
        final FragmentManager fragmentManager = getSupportFragmentManager();
        fragmentManager.popBackStackImmediate(/* name */ null,
                FragmentManager.POP_BACK_STACK_INCLUSIVE);
    }

    /**
     * Reset to Photo Picker initial launch state (Photos grid tab) in personal profile mode.
     */
    private void resetToPersonalProfile() {
        // Clear all the fragments in the FragmentManager
        clearFragments();

        // Reset all content to the personal profile
        mPickerViewModel.resetToPersonalProfile();

        // Set up the fragments same as the initial launch state
        setupInitialLaunchState();
    }

    /**
     * Reset to Photo Picker initial launch state (Photos grid tab) in user profile mode that
     * started the photopicker.
     */
    private void resetToCurrentUserProfile() {
        // Clear all the fragments in the FragmentManager
        clearFragments();

        // Reset all content to the start user profile
        mPickerViewModel.resetToCurrentUserProfile();

        // Set up the fragments same as the initial launch state
        setupInitialLaunchState();
    }

    /**
     * Reset to Photo Picker initial launch state (Photos grid tab) in the current profile mode.
     */
    private void resetInCurrentProfile(boolean shouldSendInitRequest) {
        // Clear all the fragments in the FragmentManager
        clearFragments();

        // Reset all content in the current profile
        mPickerViewModel.resetAllContentInCurrentProfile(shouldSendInitRequest);

        // Set up the fragments same as the initial launch state
        setupInitialLaunchState();
    }

    /**
     * Returns {@code true} if settings page is enabled.
     */
    private boolean shouldShowSettingsScreen() {
        if (mPickerViewModel.shouldShowOnlyLocalFeatures()) {
            return false;
        }

        final ComponentName componentName = new ComponentName(this,
                PhotoPickerSettingsActivity.class);
        return getPackageManager().getComponentEnabledSetting(componentName)
                == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    /**
     * Returns {@code true} if intent action is {@link ACTION_GET_CONTENT}.
     */
    private boolean isGetContentAction() {
        return ACTION_GET_CONTENT.equals(getIntent().getAction());
    }

    /**
     * Returns {@code true} if intent action is {@link ACTION_PICK_IMAGES}.
     */
    private boolean isPickImagesAction() {
        return ACTION_PICK_IMAGES.equals(getIntent().getAction());
    }

    /**
     * Returns {@code true} if intent action is {@link ACTION_USER_SELECT_IMAGES_FOR_APP}
     * (the 3-way storage permission grant flow)
     */
    private boolean isUserSelectImagesForAppAction() {
        return ACTION_USER_SELECT_IMAGES_FOR_APP.equals(getIntent().getAction());
    }

    private class CrossProfileListeners {
        private final List<String> mProfileFilterActions = Lists.newArrayList(
                Intent.ACTION_MANAGED_PROFILE_ADDED, // add profile button switch
                Intent.ACTION_MANAGED_PROFILE_REMOVED, // remove profile button switch
                Intent.ACTION_MANAGED_PROFILE_UNLOCKED, // activate profile button switch
                Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE // disable profile button switch
        );

        private final UserIdManager mUserIdManager;
        private final UserManagerState mUserManagerState;

        public CrossProfileListeners() {
            if (mConfigStore.isPrivateSpaceInPhotoPickerEnabled() && SdkLevel.isAtLeastV()) {
                mProfileFilterActions.add(Intent.ACTION_PROFILE_ADDED);
                mProfileFilterActions.add(Intent.ACTION_PROFILE_REMOVED);
                mProfileFilterActions.add(Intent.ACTION_PROFILE_UNAVAILABLE);
                mProfileFilterActions.add(Intent.ACTION_PROFILE_AVAILABLE);
            }

            mUserManagerState = mPickerViewModel.getUserManagerState();
            mUserIdManager = mPickerViewModel.getUserIdManager();

            registerBroadcastReceivers();
        }

        public void onDestroy() {
            unregisterReceiver(mReceiver);
        }

        private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();

                final UserHandle userHandle = intent.getParcelableExtra(Intent.EXTRA_USER);
                final UserId userId = UserId.of(userHandle);

                // We only need to refresh the layout when the received profile user is the
                // managed user corresponding to the current profile or a new work profile is added
                // for the current user.
                if (!(mConfigStore.isPrivateSpaceInPhotoPickerEnabled() && SdkLevel.isAtLeastS())
                        && !userId.equals(mUserIdManager.getManagedUserId())
                        && !action.equals(Intent.ACTION_MANAGED_PROFILE_ADDED)) {
                    return;
                }

                if (mConfigStore.isPrivateSpaceInPhotoPickerEnabled() && SdkLevel.isAtLeastV()) {
                    switch (action) {
                        case Intent.ACTION_PROFILE_ADDED:
                            handleProfileAdded();
                            break;
                        case Intent.ACTION_PROFILE_REMOVED:
                            notifyProfileRemoved(userId);
                            break;
                        case Intent.ACTION_PROFILE_UNAVAILABLE:
                            notifyProfileOff(userId);
                            break;
                        case Intent.ACTION_PROFILE_AVAILABLE:
                            handleProfileOn(userId);
                            break;
                        default:
                            // do nothing
                    }
                }

                switch (action) {
                    case Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE:
                            notifyProfileOff(userId);
                        break;
                    case Intent.ACTION_MANAGED_PROFILE_REMOVED:
                        if (!(mConfigStore.isPrivateSpaceInPhotoPickerEnabled()
                                && SdkLevel.isAtLeastV())) {
                            notifyProfileRemoved(userId);
                        }
                        break;
                    case Intent.ACTION_MANAGED_PROFILE_UNLOCKED:
                        handleProfileOn(userId);
                        break;
                    case Intent.ACTION_MANAGED_PROFILE_ADDED:
                        if (mConfigStore.isPrivateSpaceInPhotoPickerEnabled()
                                && !SdkLevel.isAtLeastV() && SdkLevel.isAtLeastS()) {
                            handleProfileAdded();
                        } else if (!(mConfigStore.isPrivateSpaceInPhotoPickerEnabled()
                                && SdkLevel.isAtLeastS())) {
                            handleWorkProfileAdded();
                        }
                        break;
                    default:
                        // do nothing
                }
            }
        };

        private void registerBroadcastReceivers() {
            final IntentFilter profileFilter = new IntentFilter();
            for (String profileAction : mProfileFilterActions) {
                profileFilter.addAction(profileAction);
            }
            registerReceiver(mReceiver, profileFilter);
        }

        private void handleTurnedOffOrRemovedProfile() {
            if (mTurnedOffProfileUserId != null) {
                handleProfileOff(mTurnedOffProfileUserId);
                mTurnedOffProfileUserId = null;
            }

            if (mRemovedProfileUserId != null) {
                if (!(mConfigStore.isPrivateSpaceInPhotoPickerEnabled() && SdkLevel.isAtLeastS())) {
                    handleWorkProfileRemoved();
                } else {
                    handleProfileRemoved(mRemovedProfileUserId);
                }
                mRemovedProfileUserId = null;
            }
        }
        private void handleWorkProfileOff() {
            if (mUserIdManager.isManagedUserSelected()) {
                switchToPersonalProfileInitialLaunchState();
            }
            mUserIdManager.updateWorkProfileOffValue();
        }
        private void notifyProfileOff(UserId userId) {
            mTurnedOffProfileUserId = userId;
            if (mIsPostResumeCallFinished) {
                handleTurnedOffOrRemovedProfile();
            }
        }

        private void notifyProfileRemoved(UserId userId) {
            mRemovedProfileUserId = userId;
            if (mIsPostResumeCallFinished) {
                handleTurnedOffOrRemovedProfile();
            }
        }
        private void handleProfileOff(UserId userId) {
            if (mConfigStore.isPrivateSpaceInPhotoPickerEnabled() && SdkLevel.isAtLeastS()) {
                if (mUserManagerState.isUserSelectedAsCurrentUserProfile(userId)) {
                    switchToCurrentUserProfileInitialLaunchState();
                }
                mUserManagerState.updateProfileOffValuesAndPostCrossProfileStatus();
                if (SdkLevel.isAtLeastV()) {
                    updateRecentsScreenshotSetting();
                }
                return;
            }
            handleWorkProfileOff();
        }


        private void handleWorkProfileRemoved() {
            if (mUserIdManager.isManagedUserSelected()) {
                switchToPersonalProfileInitialLaunchState();
            }
            mUserIdManager.resetUserIds();
        }

        private void handleProfileRemoved(UserId userId) {
            if (mConfigStore.isPrivateSpaceInPhotoPickerEnabled() && SdkLevel.isAtLeastS()) {
                if (mUserManagerState.isUserSelectedAsCurrentUserProfile(userId)) {
                    switchToCurrentUserProfileInitialLaunchState();
                }
                mUserManagerState.resetUserIdsAndSetCrossProfileValues(getIntent());
                if (SdkLevel.isAtLeastV()) {
                    updateRecentsScreenshotSetting();
                }
            }
        }

        private void handleWorkProfileAdded() {
            mUserIdManager.resetUserIds();
        }

        private void handleProfileAdded() {
            if (mConfigStore.isPrivateSpaceInPhotoPickerEnabled() && SdkLevel.isAtLeastS()) {
                mUserManagerState.resetUserIdsAndSetCrossProfileValues(getIntent());
                if (SdkLevel.isAtLeastV()) {
                    updateRecentsScreenshotSetting();
                }
            }
        }

        private void handleWorkProfileOn() {
            // Update UI for switch to profile button
            // When the managed profile becomes available, the provider may not be available
            // immediately, we need to check if it is ready before we reload the content.
            mUserIdManager.waitForMediaProviderToBeAvailable();
        }

        private void handleProfileOn(UserId userId) {
            // Update UI for switch to profile button
            // When the managed profile becomes available, the provider may not be available
            // immediately, we need to check if it is ready before we reload the content.
            if (mConfigStore.isPrivateSpaceInPhotoPickerEnabled() && SdkLevel.isAtLeastS()) {
                mUserManagerState.waitForMediaProviderToBeAvailable(userId);
                if (SdkLevel.isAtLeastV()) {
                    updateRecentsScreenshotSetting();
                }
                return;
            }
            handleWorkProfileOn();
        }

        private void switchToPersonalProfileInitialLaunchState() {
            // We reset the state of the PhotoPicker as we do not want to make any
            // assumptions on the state of the PhotoPicker when it was in Work Profile mode.
            resetToPersonalProfile();
        }

        private  void switchToCurrentUserProfileInitialLaunchState() {
            // We reset the state of the PhotoPicker as we do not want to make any
            // assumptions on the state of the PhotoPicker when it was in other Profile mode.
            resetToCurrentUserProfile();
        }
    }

    /**
     * A {@link ViewModel} class only responsible for keeping track of "active"
     * {@link SelectedMediaPreloader} instance (if any).
     * This class has to be public, since somewhere in {@link ViewModelProvider} it will try to use
     * reflection to create an instance of this class.
     */
    public static class PreloaderInstanceHolder extends ViewModel {
        @Nullable
        SelectedMediaPreloader preloader;
    }

    /**
     * Reset the picker view model content when launched with cloud features and notified to
     * refresh the UI.
     */
    private void observeRefreshUiNotificationLiveData() {
        mPickerViewModel.refreshUiLiveData()
                .observe(this, refreshRequest -> {
                    if (refreshRequest.shouldRefreshPicker()
                            && !mPickerViewModel.shouldShowOnlyLocalFeatures()) {
                        resetInCurrentProfile(refreshRequest.shouldInitPicker());
                    }
                });
    }

    @SuppressLint("NewApi")
    private void updateRecentsScreenshotSetting() {
        if (!(mConfigStore.isPrivateSpaceInPhotoPickerEnabled() && SdkLevel.isAtLeastV())) return;
        RecentsPreviewUtil.updateRecentsVisibilitySetting(mConfigStore,
                mPickerViewModel.getUserManagerState(), this);
    }
}
