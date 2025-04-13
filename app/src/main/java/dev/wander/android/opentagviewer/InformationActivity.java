package dev.wander.android.opentagviewer;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.databinding.DataBindingUtil;


import dev.wander.android.opentagviewer.databinding.ActivityInformationBinding;
import dev.wander.android.opentagviewer.ui.compat.WindowPaddingUtil;
import dev.wander.android.opentagviewer.util.android.PropertiesUtil;

public class InformationActivity extends AppCompatActivity {
    private static final String TAG = InformationActivity.class.getSimpleName();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ActivityInformationBinding binding = DataBindingUtil.setContentView(this, R.layout.activity_information);
        WindowPaddingUtil.insertUITopPadding(binding.getRoot());
        binding.setHandleClickBack(this::finish);

        if (this.getSupportActionBar() != null) {
            this.getSupportActionBar().hide();
        }

        try {
            TextView version = findViewById(R.id.appVersion);
            var info = getPackageManager().getPackageInfo(getPackageName(), 0);
            var filledVersionString = String.format(
                    getResources().getString(R.string.version_x),
                    info.versionName
            );
            version.setText(filledVersionString);

        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }



    public void onClickDeveloperWebsite(View view) {
        Log.d(TAG, "Clicked Developer Website button");

        var properties = PropertiesUtil.getProperties(this.getAssets(), "app.properties");
        assert properties != null;
        final String developerWebsite = properties.getProperty("developerWebsite");

        Uri devSite = Uri.parse(developerWebsite);
        Intent intent = new Intent(Intent.ACTION_VIEW, devSite);
        if (intent.resolveActivity(getPackageManager()) != null) {
            this.startActivity(intent);
        }
    }

    public void onClickWiki(View view) {
        Log.d(TAG, "Clicked App Wiki button");

        var properties = PropertiesUtil.getProperties(this.getAssets(), "app.properties");
        assert properties != null;
        final String projectGithub = properties.getProperty("projectWiki");

        Uri devSite = Uri.parse(projectGithub);
        Intent intent = new Intent(Intent.ACTION_VIEW, devSite);
        if (intent.resolveActivity(getPackageManager()) != null) {
            this.startActivity(intent);
        }
    }

    public void onClickGithub(View view) {
        Log.d(TAG, "Clicked Github button");

        var properties = PropertiesUtil.getProperties(this.getAssets(), "app.properties");
        assert properties != null;
        final String projectGithub = properties.getProperty("projectGithub");

        Uri devSite = Uri.parse(projectGithub);
        Intent intent = new Intent(Intent.ACTION_VIEW, devSite);
        if (intent.resolveActivity(getPackageManager()) != null) {
            this.startActivity(intent);
        }
    }
}