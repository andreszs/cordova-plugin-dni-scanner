package com.crediser.plugins.dniscanner;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.webkit.WebResourceResponse;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPluginPathHandler;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.Locale;
import java.util.Iterator;

public final class DniScannerPlugin extends CordovaPlugin {
    private static final String IMAGE_WEB_PATH = "_dni_scanner_image_/";
    private static final int PERMISSION_REQUEST = 9741;
    private static final int SCAN_REQUEST = 9742;

    private CallbackContext pendingCallback;
    private JSONObject pendingOptions;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext)
        throws JSONException {
        if (!"scan".equals(action)) {
            return false;
        }
        if (pendingCallback != null) {
            callbackContext.error(error("BUSY", "Ya existe una captura de DNI activa.", true));
            return true;
        }

        JSONObject options = args.optJSONObject(0);
        if (options == null) {
            options = new JSONObject();
        }
        String validationError = validateOptions(options);
        if (validationError != null) {
            callbackContext.error(error("INVALID_ARGUMENT", validationError, false));
            return true;
        }

        pendingCallback = callbackContext;
        pendingOptions = options;

        if (cordova.hasPermission(Manifest.permission.CAMERA)) {
            launchScanner();
        } else {
            cordova.requestPermission(this, PERMISSION_REQUEST, Manifest.permission.CAMERA);
        }
        return true;
    }

    private String validateOptions(JSONObject options) {
        String side = options.optString("side", "");
        if (!("front".equals(side) || "back".equals(side))) {
            return "La opción side debe ser 'front' o 'back'.";
        }
        String sideValidation = options.optString("sideValidation", "off");
        if (SideValidationMode.fromValue(sideValidation) == null) {
            return "La opción sideValidation debe ser 'off', 'advisory' o 'strict'.";
        }
        if (options.has("autoCapture") && !options.optBoolean("autoCapture", true)) {
            return "La versión actual sólo admite autoCapture=true.";
        }
        String flashMode = options.optString("flashMode", "off");
        if (!("off".equals(flashMode) || "auto".equals(flashMode) || "on".equals(flashMode))) {
            return "La opción flashMode debe ser 'off', 'auto' u 'on'.";
        }
        String orientation = options.optString("orientation", "portrait");
        if (!("portrait".equals(orientation)
            || "landscape".equals(orientation)
            || "auto".equals(orientation))) {
            return "La opción orientation debe ser 'portrait', 'landscape' o 'auto'.";
        }
        if (!isBooleanOption(options, "showCloseButton")) {
            return "La opción showCloseButton debe ser booleana.";
        }
        if (!isBooleanOption(options, "showFlashButton")) {
            return "La opción showFlashButton debe ser booleana.";
        }
        if (!isBooleanOption(options, "debugMode")) {
            return "La opción debugMode debe ser booleana.";
        }
        if (options.has("maxOutputLongEdge")) {
            Object rawMaximum = options.opt("maxOutputLongEdge");
            if (!(rawMaximum instanceof Number)) {
                return "La opción maxOutputLongEdge debe ser numérica.";
            }
            double maximum = ((Number) rawMaximum).doubleValue();
            if (Double.isNaN(maximum)
                || Double.isInfinite(maximum)
                || maximum != Math.rint(maximum)
                || maximum < ScannerConfig.MIN_OUTPUT_LONG_EDGE
                || maximum > ScannerConfig.MAX_OUTPUT_LONG_EDGE) {
                return "La opción maxOutputLongEdge debe ser un entero entre 640 y 2048.";
            }
        }
        if (options.has("guideMarginPercent")) {
            Object rawMargin = options.opt("guideMarginPercent");
            if (!(rawMargin instanceof Number)) {
                return "La opción guideMarginPercent debe ser numérica.";
            }
            double margin = ((Number) rawMargin).doubleValue();
            if (Double.isNaN(margin)
                || Double.isInfinite(margin)
                || margin < ScannerConfig.MIN_GUIDE_MARGIN_PERCENT
                || margin > ScannerConfig.MAX_GUIDE_MARGIN_PERCENT) {
                return "La opción guideMarginPercent debe estar entre 0 y 20.";
            }
        }
        if (options.has("cropPaddingPercent")) {
            Object rawPadding = options.opt("cropPaddingPercent");
            if (!(rawPadding instanceof Number)) {
                return "La opción cropPaddingPercent debe ser numérica.";
            }
            double padding = ((Number) rawPadding).doubleValue();
            if (Double.isNaN(padding)
                || Double.isInfinite(padding)
                || padding < ScannerConfig.MIN_CROP_PADDING_PERCENT
                || padding > ScannerConfig.MAX_CROP_PADDING_PERCENT) {
                return "La opción cropPaddingPercent debe estar entre 0 y 10.";
            }
        }
        if (options.has("instructionText")
            && !options.isNull("instructionText")
            && !(options.opt("instructionText") instanceof String)) {
            return "La opción instructionText debe ser texto, null o estar ausente.";
        }
        String checksError = validateChecks(options);
        if (checksError != null) {
            return checksError;
        }
        long timeoutMs = options.optLong("timeoutMs", 0L);
        if (timeoutMs < 0L) {
            return "La opción timeoutMs no puede ser negativa.";
        }
        return null;
    }

    private String validateChecks(JSONObject options) {
        if (!options.has("checks") || options.isNull("checks")) {
            return null;
        }
        Object rawChecks = options.opt("checks");
        if (!(rawChecks instanceof JSONObject)) {
            return "La opción checks debe ser un objeto.";
        }
        JSONObject checks = (JSONObject) rawChecks;
        Iterator<String> keys = checks.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            if (!isKnownCheck(name)) {
                return "Control desconocido en checks: " + name + ".";
            }
            if (!(checks.opt(name) instanceof Boolean)) {
                return "El control checks." + name + " debe ser booleano.";
            }
        }
        return null;
    }

    private boolean isKnownCheck(String name) {
        switch (name) {
            case "documentSize":
            case "centering":
            case "perspective":
            case "stability":
            case "sharpness":
            case "brightness":
            case "glare":
            case "shadows":
            case "edgeOcclusion":
                return true;
            default:
                return false;
        }
    }

    private boolean isBooleanOption(JSONObject options, String name) {
        return !options.has(name) || options.opt(name) instanceof Boolean;
    }

    private void launchScanner() {
        Activity activity = cordova.getActivity();
        Intent intent = new Intent(activity, DniScannerActivity.class);
        intent.putExtra(DniScannerActivity.EXTRA_OPTIONS_JSON, pendingOptions.toString());
        intent.putExtra(DniScannerActivity.EXTRA_IMAGE_WEB_BASE_URL, imageWebBaseUrl());
        cordova.startActivityForResult(this, intent, SCAN_REQUEST);
    }

    @Override
    public CordovaPluginPathHandler getPathHandler() {
        return new CordovaPluginPathHandler(path -> {
            if (path == null || !path.startsWith(IMAGE_WEB_PATH)) {
                return null;
            }
            String fileName = path.substring(IMAGE_WEB_PATH.length());
            if (!fileName.matches("dni-[0-9a-fA-F-]+\\.jpg")) {
                return emptyImageResponse();
            }
            try {
                File cacheDirectory = new File(
                    cordova.getActivity().getCacheDir(),
                    "dni-scanner"
                ).getCanonicalFile();
                File image = new File(cacheDirectory, fileName).getCanonicalFile();
                if (!cacheDirectory.equals(image.getParentFile()) || !image.isFile()) {
                    return emptyImageResponse();
                }
                return new WebResourceResponse("image/jpeg", null, new FileInputStream(image));
            } catch (Exception ignored) {
                return emptyImageResponse();
            }
        });
    }

    private String imageWebBaseUrl() {
        String scheme = preferences.getString("scheme", "https").toLowerCase(Locale.US);
        String hostname = preferences.getString("hostname", "localhost").toLowerCase(Locale.US);
        return scheme + "://" + hostname + "/" + IMAGE_WEB_PATH;
    }

    private WebResourceResponse emptyImageResponse() {
        return new WebResourceResponse(
            "image/jpeg",
            null,
            new ByteArrayInputStream(new byte[0])
        );
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults)
        throws JSONException {
        if (requestCode != PERMISSION_REQUEST || pendingCallback == null) {
            return;
        }
        boolean granted = grantResults.length > 0
            && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            launchScanner();
        } else {
            finishWithError("CAMERA_PERMISSION_DENIED", "Permiso de cámara denegado.", true);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (requestCode != SCAN_REQUEST || pendingCallback == null) {
            return;
        }

        if (resultCode == Activity.RESULT_OK && intent != null) {
            String json = intent.getStringExtra(DniScannerActivity.EXTRA_RESULT_JSON);
            try {
                pendingCallback.success(new JSONObject(json));
            } catch (Exception exception) {
                pendingCallback.error(error(
                    "PROCESSING_FAILED",
                    "El scanner devolvió un resultado inválido.",
                    false
                ));
            }
            clearPending();
            return;
        }

        String code = "USER_CANCELLED";
        String message = "Captura cancelada por el usuario.";
        boolean recoverable = true;
        if (intent != null) {
            code = intent.getStringExtra(DniScannerActivity.EXTRA_ERROR_CODE);
            message = intent.getStringExtra(DniScannerActivity.EXTRA_ERROR_MESSAGE);
            recoverable = intent.getBooleanExtra(DniScannerActivity.EXTRA_ERROR_RECOVERABLE, true);
        }
        if (code == null) {
            code = "USER_CANCELLED";
        }
        if (message == null) {
            message = "Captura cancelada por el usuario.";
        }
        pendingCallback.error(error(code, message, recoverable));
        clearPending();
    }

    private void finishWithError(String code, String message, boolean recoverable) {
        if (pendingCallback != null) {
            pendingCallback.error(error(code, message, recoverable));
        }
        clearPending();
    }

    private void clearPending() {
        pendingCallback = null;
        pendingOptions = null;
    }

    static JSONObject error(String code, String message, boolean recoverable) {
        JSONObject error = new JSONObject();
        try {
            error.put("code", code);
            error.put("message", message);
            error.put("recoverable", recoverable);
        } catch (JSONException ignored) {
            // Fixed keys and scalar values cannot fail in normal operation.
        }
        return error;
    }
}
