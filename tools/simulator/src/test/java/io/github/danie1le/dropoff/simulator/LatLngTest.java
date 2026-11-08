package io.github.danie1le.dropoff.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Random;
import org.junit.jupiter.api.Test;

class LatLngTest {

    private static final LatLng CAPITOL = new LatLng(30.2747, -97.7404);
    private static final LatLng UT_TOWER = new LatLng(30.2862, -97.7394);

    @Test
    void measuresDistanceAlongTheEarthsSurface() {
        // About 1.28 km due north, by the haversine formula.
        assertThat(CAPITOL.distanceMeters(UT_TOWER)).isCloseTo(1281, within(5.0));
        assertThat(CAPITOL.distanceMeters(CAPITOL)).isZero();
    }

    @Test
    void movesTheGivenDistanceInAStraightLineTowardTheTarget() {
        LatLng moved = CAPITOL.moveToward(UT_TOWER, 500);

        assertThat(CAPITOL.distanceMeters(moved)).isCloseTo(500, within(1.0));
        assertThat(moved.distanceMeters(UT_TOWER)).isCloseTo(CAPITOL.distanceMeters(UT_TOWER) - 500, within(1.0));
    }

    @Test
    void stopsExactlyOnTheTargetInsteadOfOvershooting() {
        assertThat(CAPITOL.moveToward(UT_TOWER, 5_000)).isEqualTo(UT_TOWER);
        assertThat(UT_TOWER.moveToward(UT_TOWER, 10)).isEqualTo(UT_TOWER);
    }

    @Test
    void randomAustinPointsStayWithinEightKilometresOfDowntown() {
        Random random = new Random(42);

        for (int i = 0; i < 1_000; i++) {
            LatLng point = Austin.randomPoint(random);
            assertThat(Austin.DOWNTOWN.distanceMeters(point)).isLessThanOrEqualTo(Austin.RADIUS_METERS + 1);
        }
    }

    @Test
    void theSameSeedGivesTheSamePoints() {
        assertThat(Austin.randomPoint(new Random(7))).isEqualTo(Austin.randomPoint(new Random(7)));
    }
}
