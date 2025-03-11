package dev.wander.android.airtagforall;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.databinding.DataBindingUtil;

import dev.wander.android.airtagforall.databinding.ActivityInformationBinding;

public class InformationActivity extends AppCompatActivity {

    private static final String TAG = InformationActivity.class.getSimpleName();

    private static final String DEVELOPER_WEBSITE = "https://wander.dev";
    private static final String GITHUB = "https://github.com/parawanderer/airtag4all";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // setContentView(R.layout.activity_information);
        ActivityInformationBinding binding = DataBindingUtil.setContentView(this, R.layout.activity_information);
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
        Log.i(TAG, "Clicked Developer Website button!");

        Uri devSite = Uri.parse(DEVELOPER_WEBSITE);
        Intent intent = new Intent(Intent.ACTION_VIEW, devSite);
        if (intent.resolveActivity(getPackageManager()) != null) {
            this.startActivity(intent);
        }
    }

    public void onClickGithub(View view) {
        Log.i(TAG, "Clicked Github button!");

        Uri devSite = Uri.parse(GITHUB);
        Intent intent = new Intent(Intent.ACTION_VIEW, devSite);
        if (intent.resolveActivity(getPackageManager()) != null) {
            this.startActivity(intent);
        }
    }
}