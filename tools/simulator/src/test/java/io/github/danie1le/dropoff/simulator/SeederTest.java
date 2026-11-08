package io.github.danie1le.dropoff.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SeederTest {

    private final FakeApi api = new FakeApi();

    @Test
    void createsTheAustinDatasetThroughTheApi() {
        SimState state = new Seeder(api).seed(42);

        assertThat(api.callsStartingWith("createMerchant")).hasSize(10);
        assertThat(api.callsStartingWith("createMenuItem")).hasSize(80);
        assertThat(api.callsStartingWith("createCustomer")).hasSize(50);
        assertThat(api.callsStartingWith("createCourier")).hasSize(30);
        // Each courier sends a first location, so the run can make it AVAILABLE.
        assertThat(api.callsStartingWith("location")).hasSize(30);

        assertThat(state.seed()).isEqualTo(42);
        assertThat(state.merchants())
                .hasSize(10)
                .allSatisfy(m -> assertThat(m.menuItemIds()).hasSize(8))
                .allSatisfy(m -> assertThat(Austin.DOWNTOWN.distanceMeters(m.location()))
                        .isLessThanOrEqualTo(Seeder.MERCHANT_RADIUS_METERS + 1));
        assertThat(state.customerIds()).hasSize(50);
        assertThat(state.couriers())
                .hasSize(30)
                .allSatisfy(c -> assertThat(Austin.DOWNTOWN.distanceMeters(c.home()))
                        .isLessThanOrEqualTo(Austin.RADIUS_METERS + 1));
    }

    @Test
    void theSameSeedCreatesTheSameData() {
        FakeApi other = new FakeApi();

        assertThat(new Seeder(api).seed(42)).isEqualTo(new Seeder(other).seed(42));
        assertThat(api.locations).isEqualTo(other.locations);
    }

    @Test
    void theStateFileRoundTrips(@TempDir Path dir) throws Exception {
        SimState state = new Seeder(api).seed(42);
        Path file = dir.resolve(".simulator/state.json");

        StateFile.write(file, state);

        assertThat(Files.exists(file)).isTrue();
        assertThat(StateFile.read(file)).isEqualTo(state);
    }
}
