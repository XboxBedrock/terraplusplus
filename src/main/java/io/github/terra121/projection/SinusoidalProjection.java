package io.github.terra121.projection;

import io.github.terra121.TerraConstants;

/**
 * Implementation of the Sinusoidal projection.
 * 
 * @see <a href="https://en.wikipedia.org/wiki/Sinusoidal_projection"> Wikipedia's article on the sinusoidal projection</a>
 *
 */
public class SinusoidalProjection extends GeographicProjection {

    @Override
    public double[] toGeo(double x, double y) {
        return new double[]{ x / Math.cos(Math.toRadians(y)), y };
    }

    @Override
    public double[] fromGeo(double longitude, double latitude) {
        return new double[]{ longitude * Math.cos(Math.toRadians(latitude)), latitude };
    }

    @Override
    public double metersPerUnit() {
        return TerraConstants.EARTH_CIRCUMFERENCE / 360.0; //gotta make good on that exact area
    }
}
