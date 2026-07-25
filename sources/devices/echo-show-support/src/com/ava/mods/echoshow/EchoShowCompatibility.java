package com.ava.mods.echoshow;

import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Locale;

/** Single source of truth for supported Echo display hardware and capability gates. */
final class EchoShowCompatibility {
    enum Soc {
        MT8163("mt8163"),
        UNKNOWN("unknown-soc");

        final String id;

        Soc(String id) {
            this.id = id;
        }
    }

    enum BluetoothChipset {
        MT76X8("mt76x8"),
        BCM43569("bcm43569"),
        UNKNOWN("unknown-bt");

        final String id;

        BluetoothChipset(String id) {
            this.id = id;
        }
    }

    enum AmbientLightSensor {
        JSA1214("jsa1214"),
        OPT3001("opt3001"),
        UNKNOWN("unknown-als");

        final String id;

        AmbientLightSensor(String id) {
            this.id = id;
        }
    }

    enum Product {
        CROWN("crown", "Echo Show 8 (2019)", Soc.MT8163,
                BluetoothChipset.MT76X8, AmbientLightSensor.JSA1214),
        CHECKERS("checkers", "Echo Show 5 (2019)", Soc.MT8163,
                BluetoothChipset.MT76X8, AmbientLightSensor.JSA1214),
        CRONOS("cronos", "Echo Show 5 (2021)", Soc.MT8163,
                BluetoothChipset.MT76X8, AmbientLightSensor.JSA1214),
        ROOK("rook", "Echo Spot (2017)", Soc.MT8163,
                BluetoothChipset.BCM43569, AmbientLightSensor.OPT3001),
        UNKNOWN("unknown", "Unknown Echo display", Soc.UNKNOWN,
                BluetoothChipset.UNKNOWN, AmbientLightSensor.UNKNOWN);

        final String codename;
        final String displayName;
        final Soc soc;
        final BluetoothChipset bluetoothChipset;
        final AmbientLightSensor ambientLightSensor;

        Product(String codename, String displayName, Soc soc,
                BluetoothChipset bluetoothChipset, AmbientLightSensor ambientLightSensor) {
            this.codename = codename;
            this.displayName = displayName;
            this.soc = soc;
            this.bluetoothChipset = bluetoothChipset;
            this.ambientLightSensor = ambientLightSensor;
        }
    }

    enum Capability {
        BLE_FEATURE_REPAIR,
        DARK_SCREEN_CONTROL,
        OVERLAY_PERMISSION,
        MT76X8_BLE_PROXY_TUNING,
        CROWN_GATT_RECOVERY
    }

    private static final String[] BOARD_REVISION_PATHS = {
            "/sys/firmware/devicetree/base/version",
            "/proc/device-tree/version"
    };
    private static final EchoShowCompatibility CURRENT = detect();

    private final Product product;
    private final String boardRevision;
    private final String detectionSource;

    private EchoShowCompatibility(Product product, String boardRevision, String detectionSource) {
        this.product = product;
        this.boardRevision = boardRevision;
        this.detectionSource = detectionSource;
    }

    static EchoShowCompatibility current() {
        return CURRENT;
    }

    Product getProduct() {
        return product;
    }

    String getCodename() {
        return product.codename;
    }

    String getBoardRevision() {
        return boardRevision;
    }

    boolean isSupported() {
        return product != Product.UNKNOWN;
    }

    boolean supports(Capability capability) {
        if (!isSupported()) {
            return false;
        }
        switch (capability) {
            case BLE_FEATURE_REPAIR:
            case OVERLAY_PERMISSION:
                return true;
            case DARK_SCREEN_CONTROL:
                return product.ambientLightSensor == AmbientLightSensor.JSA1214;
            case MT76X8_BLE_PROXY_TUNING:
                return product.bluetoothChipset == BluetoothChipset.MT76X8;
            case CROWN_GATT_RECOVERY:
                return product == Product.CROWN
                        && product.bluetoothChipset == BluetoothChipset.MT76X8;
            default:
                return false;
        }
    }

    int getMinimumBacklight() {
        return product == Product.ROOK ? 1 : 10;
    }

    int getBleProxyHandoverDelayMs() {
        return supports(Capability.MT76X8_BLE_PROXY_TUNING) ? 1000 : 0;
    }

    String getDiagnosticSummary() {
        if (!isSupported()) {
            return "Unknown hardware (" + detectionSource + "); privileged actions disabled";
        }
        String revision = boardRevision.isEmpty() ? "unknown revision" : boardRevision;
        return product.displayName + " [" + product.codename + ", " + revision + ", "
                + product.soc.id + "/" + product.bluetoothChipset.id + "/"
                + product.ambientLightSensor.id + "] via " + detectionSource;
    }

    private static EchoShowCompatibility detect() {
        Detection detection = detectProduct();
        String revision = detection.product == Product.UNKNOWN ? "" : readBoardRevision();
        return new EchoShowCompatibility(detection.product, revision, detection.source);
    }

    private static Detection detectProduct() {
        String[][] buildFields = {
                {"Build.DEVICE", Build.DEVICE},
                {"Build.PRODUCT", Build.PRODUCT},
                {"Build.BOARD", Build.BOARD},
                {"Build.MODEL", Build.MODEL}
        };
        Product selected = Product.UNKNOWN;
        StringBuilder sources = new StringBuilder();

        for (String[] field : buildFields) {
            ProductMatch fieldMatch = productFromCodenameToken(field[1]);
            if (fieldMatch.conflicting) {
                return new Detection(Product.UNKNOWN, "conflicting " + field[0] + " identifiers");
            }
            if (fieldMatch.product == Product.UNKNOWN) {
                continue;
            }
            if (selected != Product.UNKNOWN && selected != fieldMatch.product) {
                return new Detection(Product.UNKNOWN, "conflicting build identifiers");
            }
            selected = fieldMatch.product;
            if (sources.length() > 0) {
                sources.append('+');
            }
            sources.append(field[0]);
        }

        if (selected == Product.UNKNOWN) {
            return new Detection(Product.UNKNOWN, "no supported codename");
        }
        return new Detection(selected, sources.toString());
    }

    private static ProductMatch productFromCodenameToken(String value) {
        if (value == null || value.isEmpty()) {
            return new ProductMatch(Product.UNKNOWN, false);
        }
        String normalized = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        if (normalized.isEmpty()) {
            return new ProductMatch(Product.UNKNOWN, false);
        }

        Product match = Product.UNKNOWN;
        for (String token : normalized.split("\\s+")) {
            for (Product candidate : Product.values()) {
                if (candidate == Product.UNKNOWN || !candidate.codename.equals(token)) {
                    continue;
                }
                if (match != Product.UNKNOWN && match != candidate) {
                    return new ProductMatch(Product.UNKNOWN, true);
                }
                match = candidate;
            }
        }
        return new ProductMatch(match, false);
    }

    private static String readBoardRevision() {
        for (String path : BOARD_REVISION_PATHS) {
            String value = readSmallTextFile(new File(path));
            if (!value.isEmpty() && value.matches("[A-Za-z0-9._-]{1,64}")) {
                return value.toLowerCase(Locale.ROOT);
            }
        }
        return "";
    }

    private static String readSmallTextFile(File file) {
        if (!file.isFile() || !file.canRead()) {
            return "";
        }
        byte[] buffer = new byte[128];
        try (FileInputStream input = new FileInputStream(file)) {
            int count = input.read(buffer);
            if (count <= 0) {
                return "";
            }
            return new String(buffer, 0, count, "UTF-8")
                    .replace("\u0000", "")
                    .trim();
        } catch (IOException | SecurityException ignored) {
            return "";
        }
    }

    private static final class Detection {
        final Product product;
        final String source;

        Detection(Product product, String source) {
            this.product = product;
            this.source = source;
        }
    }

    private static final class ProductMatch {
        final Product product;
        final boolean conflicting;

        ProductMatch(Product product, boolean conflicting) {
            this.product = product;
            this.conflicting = conflicting;
        }
    }
}
