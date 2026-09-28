package com.zomdroid.fragments;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.zomdroid.ControlsEditorActivity;
import com.zomdroid.R;
import com.zomdroid.databinding.FragmentControlsEditorLaunchBinding;
import com.zomdroid.game.GameInstance;
import com.zomdroid.game.GameInstanceManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class ControlsEditorLaunchFragment extends Fragment {

    private FragmentControlsEditorLaunchBinding binding;
    private List<GameInstance> instances;
    private GameInstance selectedInstance = null;

    private static final String BACKGROUND_FILENAME = "editor_background.jpg";

    private final ActivityResultLauncher<String> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;
                if (selectedInstance == null) {
                    Toast.makeText(requireContext(),
                            R.string.select_instance,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                saveBackgroundImage(uri);
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentControlsEditorLaunchBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.installControlsBannerIv.setImageResource(R.drawable.banner_default);

        setupInstanceSpinner();

        binding.controlsEditorBgBrowseIb.setOnClickListener(v -> {
            if (selectedInstance == null) {
                Toast.makeText(requireContext(),
                        R.string.select_instance,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            pickImageLauncher.launch("image/*");
        });

        binding.controlsEditorBgClearBtn.setOnClickListener(v -> {
            if (selectedInstance == null) return;
            File bgFile = getBackgroundFile(selectedInstance);
            if (bgFile.exists()) bgFile.delete();
            refreshBackgroundState();
            Toast.makeText(requireContext(),
                    R.string.controls_editor_bg_cleared,
                    Toast.LENGTH_SHORT).show();
        });

        binding.controlsEditorOpenBtn.setOnClickListener(v -> {
            if (selectedInstance == null) {
                Toast.makeText(requireContext(),
                        R.string.select_instance,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(requireContext(), ControlsEditorActivity.class);
            intent.putExtra(ControlsEditorActivity.EXTRA_INSTANCE_NAME,
                    selectedInstance.getName());
            File bgFile = getBackgroundFile(selectedInstance);
            if (bgFile.exists()) {
                intent.putExtra(ControlsEditorActivity.EXTRA_BACKGROUND_PATH,
                        bgFile.getAbsolutePath());
            }
            startActivity(intent);
        });
    }

    private void setupInstanceSpinner() {
        instances = GameInstanceManager.requireSingleton().getInstances();
        List<String> names = new ArrayList<>();

        if (instances == null || instances.isEmpty()) {
            instances = new ArrayList<>();
            names.add(getString(R.string.select_instance));
        } else {
            if (instances.size() > 1) {
                names.add(getString(R.string.select_instance));
            }
            for (GameInstance gi : instances) names.add(gi.getName());
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                R.layout.spinner_item,
                names);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        binding.installControlsInstanceSpinner.setAdapter(adapter);

        binding.installControlsInstanceSpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view,
                                               int position, long id) {
                        int idx = instances.size() > 1 ? position - 1 : position;
                        if (idx < 0 || idx >= instances.size()) {
                            selectedInstance = null;
                            binding.installControlsBannerIv
                                    .setImageResource(R.drawable.banner_default);
                            binding.installControlsBannerOverlay
                                    .setVisibility(View.INVISIBLE);
                            refreshBackgroundState();
                            return;
                        }
                        selectedInstance = instances.get(idx);
                        updateBanner(selectedInstance);
                        refreshBackgroundState();
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {}
                });

        // Auto-select if only one instance
        if (instances.size() == 1) {
            selectedInstance = instances.get(0);
            updateBanner(selectedInstance);
            refreshBackgroundState();
        }
    }

    private void updateBanner(GameInstance gi) {
        File bannerFile = new File(gi.getHomePath(), "banner.png");
        if (bannerFile.exists()) {
            binding.installControlsBannerIv.setImageURI(Uri.fromFile(bannerFile));
            binding.installControlsBannerOverlay.setVisibility(View.VISIBLE);
        } else {
            binding.installControlsBannerIv.setImageResource(R.drawable.banner_default);
            binding.installControlsBannerOverlay.setVisibility(View.INVISIBLE);
        }
    }

    private File getBackgroundFile(GameInstance gi) {
        File controlsDir = new File(gi.getHomePath(), "game/controls");
        controlsDir.mkdirs();
        return new File(controlsDir, BACKGROUND_FILENAME);
    }

    private void refreshBackgroundState() {
        if (selectedInstance == null) {
            binding.controlsEditorBgPathEt.setText(
                    getString(R.string.game_instance_no_file_selected));
            binding.controlsEditorBgClearBtn.setVisibility(View.GONE);
            return;
        }
        File bgFile = getBackgroundFile(selectedInstance);
        boolean hasBg = bgFile.exists();
        binding.controlsEditorBgPathEt.setText(
                hasBg ? bgFile.getName()
                        : getString(R.string.game_instance_no_file_selected));
        binding.controlsEditorBgClearBtn.setVisibility(hasBg ? View.VISIBLE : View.GONE);
    }

    private void saveBackgroundImage(Uri uri) {
        try (InputStream in = requireContext().getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(getBackgroundFile(selectedInstance))) {
            if (in == null) throw new Exception("Cannot open image");
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            refreshBackgroundState();
            Toast.makeText(requireContext(),
                    R.string.controls_editor_bg_saved,
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(),
                    getString(R.string.controls_editor_bg_error, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}