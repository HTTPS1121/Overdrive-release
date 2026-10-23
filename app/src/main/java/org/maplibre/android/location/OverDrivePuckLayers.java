package org.maplibre.android.location;

import androidx.annotation.NonNull;

import org.maplibre.android.style.layers.Layer;

/** Creates MapLibre's native location-indicator layer, whose class is package-private. */
public final class OverDrivePuckLayers {

    private OverDrivePuckLayers() {
    }

    @NonNull
    public static Layer create(@NonNull String id) {
        return new LocationIndicatorLayer(id);
    }
}
