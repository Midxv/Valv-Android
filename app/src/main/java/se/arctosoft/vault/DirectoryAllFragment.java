package se.arctosoft.vault;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import java.util.ArrayList;
import java.util.List;

import se.arctosoft.vault.data.GalleryFile;
import se.arctosoft.vault.data.Password;
import se.arctosoft.vault.data.UniqueLinkedList;
import se.arctosoft.vault.utils.FileStuff;
import se.arctosoft.vault.utils.Settings;

public class DirectoryAllFragment extends DirectoryBaseFragment {
    private static final String TAG = "DirectoryAllFragment";

    private int foundFiles = 0, foundFolders = 0;
    private com.google.android.material.bottomnavigation.BottomNavigationView bottomNav;
    private boolean isNavPillHidden = false; // The lock that prevents scroll lag!

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.layoutFabsAdd.setVisibility(View.GONE);
        binding.noMedia.setVisibility(View.GONE);
    }

    public void init() {
        Context context = requireContext();
        settings = Settings.getInstance(context);

        OnBackPressedCallback callback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (galleryViewModel.isViewpagerVisible()) {
                    showViewpager(false, galleryViewModel.getCurrentPosition(), false);
                } else if (galleryViewModel.isInSelectionMode()) {
                    galleryGridAdapter.onSelectionModeChanged(false);
                } else if (!navController.popBackStack()) {
                    FragmentActivity activity = requireActivity();
                    Password.lock(activity, false);
                    activity.finish();
                    if (!settings.exitOnLock()) {
                        startActivity(new Intent(context, MainActivity.class));
                    }
                }
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), callback);

        galleryViewModel.setAllFolder(true);
        galleryViewModel.setRootDir(false);
        if (!initActionBar(true)) { // getSupportActionBar() is null directly after orientation change
            binding.recyclerView.post(() -> initActionBar(true));
        }

        galleryViewModel.setOnAdapterItemChanged(pos -> {
            galleryPagerAdapter.notifyItemChanged(pos);
            galleryGridAdapter.notifyItemChanged(pos);
        });

        setupViewpager();
        setupGrid();
        setClickListeners();

        if (!galleryViewModel.isInitialised()) {
            findAllFiles();
        }

        // --- RESPONSIVE BOTTOM NAV LOGIC ---
        bottomNav = binding.getRoot().findViewById(R.id.bottom_navigation);
        if (bottomNav != null) {
            bottomNav.setVisibility(View.VISIBLE);
            bottomNav.setSelectedItemId(R.id.nav_all_files);

            bottomNav.setOnItemSelectedListener(item -> {
                int id = item.getItemId();
                if (id == R.id.nav_albums) {
                    navController.popBackStack();
                    return true;
                } else if (id == R.id.nav_all_files) {
                    return true;
                }
                return false;
            });

            // Smooth, Optimized Hide-on-Scroll Animation
            binding.recyclerView.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull androidx.recyclerview.widget.RecyclerView recyclerView, int dx, int dy) {
                    super.onScrolled(recyclerView, dx, dy);

                    // Don't animate if fullscreen media is open
                    if (galleryViewModel.isViewpagerVisible()) return;

                    // Ensure we only trigger the animation ONCE per state change to prevent lag
                    if (dy > 15 && !isNavPillHidden) {
                        isNavPillHidden = true; // Lock it
                        bottomNav.animate().translationY(bottomNav.getHeight() + 150).setDuration(200).start();
                    } else if (dy < -15 && isNavPillHidden) {
                        isNavPillHidden = false; // Unlock it
                        bottomNav.animate().translationY(0).setDuration(200).start();
                    }
                }
            });
        }
        // -----------------------------

        initViewModels();
    }

    private void setClickListeners() {
        binding.fabRemoveFolders.setOnClickListener(v -> {
            deleteViewModel.getFilesToDelete().clear();
            deleteViewModel.getFilesToDelete().addAll(galleryGridAdapter.getSelectedFiles());

            BottomSheetDeleteFragment bottomSheetDeleteFragment = new BottomSheetDeleteFragment();
            FragmentManager childFragmentManager = getChildFragmentManager();
            bottomSheetDeleteFragment.show(childFragmentManager, null);
        });
    }

    void onSelectionModeChanged(boolean inSelectionMode) {
        galleryViewModel.setInSelectionMode(inSelectionMode);
        if (inSelectionMode) {
            binding.layoutFabsRemoveFolders.setVisibility(View.VISIBLE);
        } else {
            binding.layoutFabsRemoveFolders.setVisibility(View.GONE);
        }
        requireActivity().invalidateOptionsMenu();
    }

    void addRootFolders() {

    }

    private void findAllFiles() {
        Log.e(TAG, "findAllFiles: ");
        foundFiles = 0;
        foundFolders = 0;
        setLoading(true);
        new Thread(() -> {
            List<Uri> directories = settings.getGalleryDirectoriesAsUri(true);
            FragmentActivity activity = getActivity();
            if (activity == null || !isSafe()) {
                return;
            }
            List<Uri> uriFiles = new ArrayList<>(directories.size());
            for (Uri uri : directories) {
                DocumentFile documentFile = DocumentFile.fromTreeUri(activity, uri);
                if (documentFile.canRead()) {
                    uriFiles.add(documentFile.getUri());
                }
            }

            activity.runOnUiThread(this::setLoadingAllWithProgress);

            List<GalleryFile> folders = new ArrayList<>();
            List<GalleryFile> files = new UniqueLinkedList<>();
            long start = System.currentTimeMillis();
            List<GalleryFile> filesToSearch = new ArrayList<>();
            for (Uri uri : uriFiles) {
                List<GalleryFile> filesInFolder = FileStuff.getFilesInFolder(activity, uri);
                for (GalleryFile foundFile : filesInFolder) {
                    if (foundFile.isDirectory()) {
                        Log.e(TAG, "findAllFiles: found " + foundFile.getNameWithPath());
                        boolean add = true;
                        for (GalleryFile addedFile : filesToSearch) {
                            if (foundFile.getNameWithPath().startsWith(addedFile.getNameWithPath() + "/")) {
                                // Do not add e.g. folder Pictures/a/b if folder Pictures/a have already been added as it will be searched by a thread in findAllFilesInFolder().
                                // Prevents showing duplicate files
                                add = false;
                                Log.e(TAG, "findAllFiles: not adding nested " + foundFile.getNameWithPath());
                                break;
                            }
                        }
                        if (add) {
                            filesToSearch.add(foundFile);
                        }
                    } else {
                        filesToSearch.add(foundFile);
                    }
                }
            }
            for (GalleryFile galleryFile : filesToSearch) {
                if (galleryFile.isDirectory()) {
                    folders.add(galleryFile);
                } else {
                    files.add(galleryFile);
                }
            }

            incrementFiles(files.size());

            activity.runOnUiThread(this::setLoadingAllWithProgress);

            List<Thread> threads = new ArrayList<>();
            for (GalleryFile galleryFile : folders) {
                if (galleryFile.isDirectory()) {
                    Thread t = new Thread(() -> {
                        List<GalleryFile> allFilesInFolder = findAllFilesInFolder(galleryFile.getUri());
                        synchronized (LOCK) {
                            files.addAll(allFilesInFolder);
                        }
                    });
                    threads.add(t);
                    t.start();
                }
            }
            for (Thread t : threads) {
                if (!isSafe()) {
                    return;
                }
                try {
                    t.join();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            Log.e(TAG, "findAllFiles: joined, found " + files.size() + ", took " + (System.currentTimeMillis() - start));
            if (!isSafe()) {
                return;
            }

            files.sort(GalleryFile::compareTo);

            activity.runOnUiThread(() -> {
                setLoading(false);
                if (files.size() > MIN_FILES_FOR_FAST_SCROLL) {
                    binding.recyclerView.setFastScrollEnabled(true);
                }
                if (galleryViewModel.isInitialised()) {
                    return;
                }
                galleryViewModel.addGalleryFiles(files);
                galleryViewModel.setInitialised(true);
                galleryGridAdapter.notifyItemRangeInserted(0, files.size());
                galleryPagerAdapter.notifyItemRangeInserted(0, files.size());
            });
        }).start();
    }

    private void setLoadingAllWithProgress() {
        if (!isSafe()) {
            return;
        }
        binding.cLLoading.cLLoading.setVisibility(View.VISIBLE);
        binding.cLLoading.txtProgress.setText(getString(R.string.gallery_loading_all_progress, foundFiles, foundFolders));
        binding.cLLoading.txtProgress.setVisibility(View.VISIBLE);
    }

    private synchronized void incrementFiles(int amount) {
        foundFiles += amount;
    }

    private synchronized void incrementFolders(int amount) {
        foundFolders += amount;
    }

    @NonNull
    private List<GalleryFile> findAllFilesInFolder(Uri uri) {
        Log.e(TAG, "findAllFilesInFolder: find all files in " + uri.getLastPathSegment());
        List<GalleryFile> files = new UniqueLinkedList<>();
        FragmentActivity activity = getActivity();
        if (activity == null || !isSafe()) {
            return files;
        }
        incrementFolders(1);
        List<GalleryFile> filesInFolder = FileStuff.getFilesInFolder(activity, uri);
        for (GalleryFile galleryFile : filesInFolder) {
            if (!isSafe()) {
                return files;
            }
            if (galleryFile.isDirectory()) {
                activity.runOnUiThread(this::setLoadingAllWithProgress);
                files.addAll(findAllFilesInFolder(galleryFile.getUri()));
            } else {
                files.add(galleryFile);
            }
        }
        incrementFiles(files.size());
        activity.runOnUiThread(this::setLoadingAllWithProgress);
        return files;
    }

    @Override
    void showViewpager(boolean show, int pos, boolean animate) {
        if (bottomNav != null) {
            bottomNav.setVisibility(show ? View.GONE : View.VISIBLE);
        }

        // 1. Setup a buttery smooth, modern Fade transition
        android.transition.Transition transition = new android.transition.Fade();
        transition.setDuration(250); // Snappy speed
        transition.setInterpolator(new androidx.interpolator.view.animation.FastOutSlowInInterpolator());

        // 2. Apply it to the root view BEFORE changing visibility
        android.transition.TransitionManager.beginDelayedTransition(binding.getRoot(), transition);

        // 3. Force the ViewPager visibility here to trigger our modern fade
        binding.viewPager.setVisibility(show ? View.VISIBLE : View.GONE);

        // 4. Pass 'false' to super so it completely ignores the old, clunky slide animation
        super.showViewpager(show, pos, false);
    }
}