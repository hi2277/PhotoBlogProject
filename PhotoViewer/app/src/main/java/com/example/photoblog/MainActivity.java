package com.example.photoblog;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private EditText serverUrl;
    private EditText tokenInput;
    private EditText titleInput;
    private EditText textInput;
    private TextView resultText;
    private ImageView imageView;
    private LinearLayout postsContainer;
    private Button syncButton;
    private Button selectButton;
    private Button uploadButton;

    private Uri selectedImage;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String> imagePicker =
            registerForActivityResult(
                    new ActivityResultContracts.GetContent(),
                    uri -> {
                        if (uri != null) {
                            selectedImage = uri;
                            imageView.setVisibility(View.VISIBLE);
                            imageView.setImageURI(uri);
                            resultText.setText(
                                    "Photo selected. Enter title and description."
                            );
                        }
                    }
            );

    private static class Photo {
        String title;
        String text;
        Bitmap bitmap;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        serverUrl = findViewById(R.id.serverUrl);
        tokenInput = findViewById(R.id.tokenInput);
        titleInput = findViewById(R.id.titleInput);
        textInput = findViewById(R.id.textInput);
        resultText = findViewById(R.id.resultText);
        imageView = findViewById(R.id.imageView);
        postsContainer = findViewById(R.id.postsContainer);
        syncButton = findViewById(R.id.syncButton);
        selectButton = findViewById(R.id.selectButton);
        uploadButton = findViewById(R.id.uploadButton);

        selectButton.setOnClickListener(
                v -> imagePicker.launch("image/*")
        );
        syncButton.setOnClickListener(v -> syncPhotos());
        uploadButton.setOnClickListener(v -> uploadPhoto());
    }

    private String baseUrl() {
        return serverUrl.getText().toString().trim()
                .replaceAll("/+$", "");
    }

    private String token() {
        return tokenInput.getText().toString().trim();
    }

    private boolean checkConnectionInputs() {
        String base = baseUrl();

        if (!base.startsWith("http://")
                && !base.startsWith("https://")) {
            resultText.setText(
                    "Enter a server URL starting with http:// or https://"
            );
            return false;
        }

        if (token().isEmpty()) {
            resultText.setText("Please enter your API token.");
            return false;
        }

        return true;
    }

    private void setBusy(boolean busy) {
        syncButton.setEnabled(!busy);
        selectButton.setEnabled(!busy);
        uploadButton.setEnabled(!busy);
        serverUrl.setEnabled(!busy);
        tokenInput.setEnabled(!busy);
        titleInput.setEnabled(!busy);
        textInput.setEnabled(!busy);
    }

    private HttpURLConnection connect(
            String address, String method
    ) throws IOException {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(address).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        return connection;
    }

    private byte[] readBytes(
            InputStream stream, int limit
    ) throws IOException {
        if (stream == null) {
            throw new IOException("Empty response.");
        }

        try (InputStream input = stream;
             ByteArrayOutputStream output =
                     new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;

            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > limit) {
                    throw new IOException(
                            "File or response is too large."
                    );
                }
                output.write(buffer, 0, count);
            }

            return output.toByteArray();
        }
    }

    private String readResponse(
            HttpURLConnection connection
    ) throws IOException {
        int status = connection.getResponseCode();
        InputStream stream = status >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();

        String body = stream == null ? ""
                : new String(
                readBytes(stream, 5 * 1024 * 1024),
                StandardCharsets.UTF_8
        );

        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + ": " + body);
        }

        return body;
    }

    private Bitmap downloadImage(String address) throws IOException {
        HttpURLConnection connection = connect(address, "GET");

        try {
            if (connection.getResponseCode() != 200) {
                throw new IOException("Could not download photo.");
            }

            byte[] bytes = readBytes(
                    connection.getInputStream(),
                    20 * 1024 * 1024
            );

            BitmapFactory.Options options =
                    new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.length, options
            );

            options.inSampleSize = 1;
            while (options.outWidth / options.inSampleSize > 1200
                    || options.outHeight / options.inSampleSize > 1200) {
                options.inSampleSize *= 2;
            }

            options.inJustDecodeBounds = false;
            Bitmap bitmap = BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.length, options
            );

            if (bitmap == null) {
                throw new IOException("Invalid photo data.");
            }

            return bitmap;
        } finally {
            connection.disconnect();
        }
    }

    private void syncPhotos() {
        if (!checkConnectionInputs()) {
            return;
        }

        final String address = baseUrl() + "/api_root/Post/";
        final String apiToken = token();

        setBusy(true);
        resultText.setText("Syncing photos...");

        executor.execute(() -> {
            try {
                HttpURLConnection connection =
                        connect(address, "GET");
                String body;

                try {
                    connection.setRequestProperty(
                            "Authorization", "Token " + apiToken
                    );
                    connection.setRequestProperty(
                            "Accept", "application/json"
                    );
                    body = readResponse(connection);
                } finally {
                    connection.disconnect();
                }

                JSONArray array = new JSONArray(body);
                List<Photo> photos = new ArrayList<>();

                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    Photo photo = new Photo();
                    photo.title = item.getString("title");
                    photo.text = item.getString("text");

                    String imageAddress = item.optString("image", "");
                    if (!imageAddress.isEmpty()
                            && !imageAddress.equals("null")) {
                        photo.bitmap = downloadImage(imageAddress);
                    }

                    photos.add(photo);
                }

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    showPhotos(photos);
                    resultText.setText(
                            "Sync complete: " + photos.size() + " photos."
                    );
                    setBusy(false);
                });
            } catch (Exception error) {
                showError("Sync failed: " + error.getMessage());
            }
        });
    }

    private void showPhotos(List<Photo> photos) {
        postsContainer.removeAllViews();

        for (Photo photo : photos) {
            TextView title = new TextView(this);
            title.setText(photo.title);
            title.setTextSize(20);
            title.setPadding(0, dp(20), 0, dp(8));
            postsContainer.addView(title);

            if (photo.bitmap != null) {
                ImageView image = new ImageView(this);
                image.setLayoutParams(
                        new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                dp(220)
                        )
                );
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setContentDescription(photo.title);
                image.setImageBitmap(photo.bitmap);
                postsContainer.addView(image);
            }

            TextView description = new TextView(this);
            description.setText(photo.text);
            description.setTextSize(16);
            description.setPadding(0, dp(8), 0, dp(16));
            postsContainer.addView(description);
        }
    }

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density
        );
    }

    private void writeText(
            ByteArrayOutputStream output, String value
    ) throws IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private void writeField(
            ByteArrayOutputStream output,
            String boundary,
            String name,
            String value
    ) throws IOException {
        writeText(output, "--" + boundary + "\r\n");
        writeText(
                output,
                "Content-Disposition: form-data; name=\""
                        + name + "\"\r\n\r\n"
        );
        writeText(output, value + "\r\n");
    }

    private void uploadPhoto() {
        if (!checkConnectionInputs()) {
            return;
        }

        final String title = titleInput.getText().toString().trim();
        final String text = textInput.getText().toString().trim();
        final Uri imageUri = selectedImage;
        final String address = baseUrl() + "/api_root/Post/";
        final String apiToken = token();

        if (title.isEmpty() || text.isEmpty() || imageUri == null) {
            resultText.setText(
                    "Enter title and description, then select a photo."
            );
            return;
        }

        setBusy(true);
        resultText.setText("Uploading photo...");

        executor.execute(() -> {
            try {
                byte[] imageBytes = readBytes(
                        getContentResolver().openInputStream(imageUri),
                        15 * 1024 * 1024
                );

                String mime = getContentResolver().getType(imageUri);
                String extension;

                if ("image/png".equals(mime)) {
                    extension = ".png";
                } else if ("image/webp".equals(mime)) {
                    extension = ".webp";
                } else if ("image/jpeg".equals(mime)) {
                    extension = ".jpg";
                } else {
                    throw new IOException("Please select JPG, PNG or WebP.");
                }

                String boundary = "PhotoBlog-" + UUID.randomUUID();
                byte[] payload;

                try (ByteArrayOutputStream output =
                             new ByteArrayOutputStream()) {
                    writeField(output, boundary, "title", title);
                    writeField(output, boundary, "text", text);
                    writeText(output, "--" + boundary + "\r\n");
                    writeText(
                            output,
                            "Content-Disposition: form-data; "
                                    + "name=\"image\"; filename=\"upload"
                                    + extension + "\"\r\n"
                    );
                    writeText(
                            output,
                            "Content-Type: " + mime + "\r\n\r\n"
                    );
                    output.write(imageBytes);
                    writeText(output, "\r\n--" + boundary + "--\r\n");
                    payload = output.toByteArray();
                }

                HttpURLConnection connection =
                        connect(address, "POST");
                String body;
                int status;

                try {
                    connection.setDoOutput(true);
                    connection.setRequestProperty(
                            "Authorization", "Token " + apiToken
                    );
                    connection.setRequestProperty(
                            "Accept", "application/json"
                    );
                    connection.setRequestProperty(
                            "Content-Type",
                            "multipart/form-data; boundary=" + boundary
                    );
                    connection.setFixedLengthStreamingMode(payload.length);

                    try (OutputStream output =
                                 connection.getOutputStream()) {
                        output.write(payload);
                    }

                    body = readResponse(connection);
                    status = connection.getResponseCode();
                } finally {
                    connection.disconnect();
                }

                if (status != 201) {
                    throw new IOException(
                            "Expected HTTP 201, received " + status
                    );
                }

                int postId = new JSONObject(body).getInt("id");

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    resultText.setText(
                            "Upload successful! HTTP 201, Post ID: "
                                    + postId
                                    + ". Tap Sync Photos to refresh."
                    );
                    titleInput.setText("");
                    textInput.setText("");
                    selectedImage = null;
                    imageView.setImageDrawable(null);
                    imageView.setVisibility(View.GONE);
                    setBusy(false);
                });
            } catch (Exception error) {
                showError("Upload failed: " + error.getMessage());
            }
        });
    }

    private void showError(String message) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            resultText.setText(message);
            setBusy(false);
        });
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}