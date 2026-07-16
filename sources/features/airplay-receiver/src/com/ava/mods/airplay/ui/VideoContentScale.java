package com.ava.mods.airplay.ui;

/** Upstream {@code VideoContentScale} for HLS surface framing. */
public enum VideoContentScale {
    BEST_FIT,
    STRETCH,
    CROP,
    HUNDRED_PERCENT;

    public VideoContentScale next() {
        VideoContentScale[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    public String label() {
        switch (this) {
            case STRETCH: return "Stretch";
            case CROP: return "Crop";
            case HUNDRED_PERCENT: return "100%";
            case BEST_FIT:
            default: return "Best fit";
        }
    }

    /** Asset name under {@code assets/video_icons/} matching Compose Icons.Rounded.*. */
    public String iconAsset() {
        switch (this) {
            case STRETCH: return "aspect_ratio";
            case CROP: return "crop_landscape";
            case HUNDRED_PERCENT: return "photo_size_select_actual";
            case BEST_FIT:
            default: return "fit_screen";
        }
    }
}
