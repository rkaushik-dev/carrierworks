package io.github.rkaushikdev.carrierworks.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.RequirementSet;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class QueueTest {

    private static final Instant NOW = Instant.parse("2025-08-11T09:00:00Z");

    private static RequirementSet wanted(String... requirements) {
        return RequirementSet.parse(List.of(requirements));
    }

    private static RequestQueue queue() {
        return new RequestQueue();
    }

    private static Instant at(Duration offset) {
        return NOW.plus(offset);
    }

    private static List<String> ids(List<Request> requests) {
        return requests.stream().map(Request::id).toList();
    }

    @Test
    void aRequestGoesOnTheQueue() {
        RequestQueue live = queue();
        Request request = live.submit("signal-processing", "capture run", wanted("adc-14bit"), NOW);
        assertThat(request.id()).isEqualTo("req-00001");
        assertThat(request.holder()).isEqualTo("signal-processing");
        assertThat(request.isOpen()).isTrue();
        assertThat(live.pending()).containsExactly(request);
    }

    @Test
    void priorityComesFirst() {
        RequestQueue live = queue();
        live.submit(
                "team-a", "experiment", wanted(), NOW, Request.Terms.standard().withPriority(7));
        Request urgent = live.submit(
                "team-b",
                "release check",
                wanted(),
                at(Duration.ofMinutes(1)),
                Request.Terms.standard().withPriority(2));
        assertThat(live.head()).map(Request::id).contains(urgent.id());
    }

    @Test
    void fairShareBreaksAPriorityTie() {
        RequestQueue live = queue();
        Request hog = live.submit("nightly", "regression", wanted(), NOW);
        Request person = live.submit("dana", "bring up", wanted(), at(Duration.ofMinutes(5)));
        List<Request> ordered = live.ordered(Map.of("nightly", 4, "dana", 0));
        assertThat(ids(ordered)).containsExactly(person.id(), hog.id());
    }

    @Test
    void waitingTimeBreaksTheRemainingTie() {
        RequestQueue live = queue();
        Request first = live.submit("team-a", "one", wanted(), NOW);
        Request second = live.submit("team-b", "two", wanted(), at(Duration.ofMinutes(1)));
        assertThat(ids(live.ordered())).containsExactly(first.id(), second.id());
    }

    @Test
    void holdingsAreReadByHolderNameHoweverItWasWritten() {
        RequestQueue live = queue();
        Request hog = live.submit("Signal Processing", "regression", wanted(), NOW);
        Request other = live.submit("firmware", "bring up", wanted(), at(Duration.ofMinutes(5)));
        List<Request> ordered = live.ordered(Map.of("signal processing", 3));
        assertThat(ids(ordered)).containsExactly(other.id(), hog.id());
    }

    @Test
    void aCancelledRequestLeavesTheQueue() {
        RequestQueue live = queue();
        Request request = live.submit("team-a", "one", wanted(), NOW);
        live.cancel(request.id(), at(Duration.ofMinutes(2)));
        assertThat(live.pending()).isEmpty();
        assertThat(live.request(request.id()).isOpen()).isFalse();
        assertThat(live.head()).isEmpty();
    }

    @Test
    void aSatisfiedRequestLeavesTheQueueToo() {
        RequestQueue live = queue();
        Request request = live.submit("team-a", "one", wanted(), NOW);
        live.satisfy(request.id(), at(Duration.ofMinutes(3)));
        assertThat(live.pending()).isEmpty();
        assertThat(live.request(request.id()).satisfiedAt()).contains(at(Duration.ofMinutes(3)));
    }

    @Test
    void aClosedRequestCannotBeClosedAgain() {
        RequestQueue live = queue();
        Request request = live.submit("team-a", "one", wanted(), NOW);
        live.satisfy(request.id(), at(Duration.ofMinutes(1)));
        assertThatThrownBy(() -> live.cancel(request.id(), at(Duration.ofMinutes(2))))
                .isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> live.satisfy(request.id(), at(Duration.ofMinutes(2))))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aRequestNobodySubmittedIsANotFound() {
        assertThatThrownBy(() -> queue().request("req-09999")).isInstanceOf(NotFound.class);
    }

    @Test
    void positionCountsFromOneAndZeroMeansNotWaiting() {
        RequestQueue live = queue();
        Request first = live.submit("team-a", "one", wanted(), NOW);
        Request second = live.submit("team-b", "two", wanted(), at(Duration.ofMinutes(1)));
        assertThat(live.positionOf(first.id())).isEqualTo(1);
        assertThat(live.positionOf(second.id())).isEqualTo(2);
        live.satisfy(first.id(), at(Duration.ofMinutes(2)));
        assertThat(live.positionOf(first.id())).isZero();
        assertThat(live.positionOf(second.id())).isEqualTo(1);
    }

    @Test
    void positionMovesWithFairShare() {
        RequestQueue live = queue();
        Request hog = live.submit("nightly", "regression", wanted(), NOW);
        live.submit("dana", "bring up", wanted(), at(Duration.ofMinutes(1)));
        assertThat(live.positionOf(hog.id())).isEqualTo(1);
        assertThat(live.positionOf(hog.id(), Map.of("nightly", 2))).isEqualTo(2);
    }

    @Test
    void howLongARequestHasWaited() {
        RequestQueue live = queue();
        Request request = live.submit("team-a", "one", wanted(), NOW);
        assertThat(request.waited(at(Duration.ofMinutes(20)))).isEqualTo(Duration.ofMinutes(20));
        Request done = live.satisfy(request.id(), at(Duration.ofMinutes(5)));
        assertThat(done.waited(at(Duration.ofHours(2)))).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void theLongestWaitOnTheQueue() {
        RequestQueue live = queue();
        live.submit("team-a", "one", wanted(), NOW);
        live.submit("team-b", "two", wanted(), at(Duration.ofMinutes(10)));
        assertThat(live.longestWait(at(Duration.ofMinutes(30)))).isEqualTo(Duration.ofMinutes(30));
        assertThat(new RequestQueue().longestWait(NOW)).isEqualTo(Duration.ZERO);
    }

    @Test
    void whatOneTeamIsWaitingOn() {
        RequestQueue live = queue();
        live.submit("team-a", "one", wanted(), NOW);
        live.submit("team-a", "two", wanted(), at(Duration.ofMinutes(1)));
        live.submit("team-b", "three", wanted(), at(Duration.ofMinutes(2)));
        assertThat(live.waitingFor("team-a")).hasSize(2);
        assertThat(live.waitingFor("team-c")).isEmpty();
    }

    @Test
    void aRequestKeepsWhatItAskedFor() {
        RequestQueue live = queue();
        Request request = live.submit(
                "team-a",
                "capture",
                wanted("adc-14bit", "transceivers>=4"),
                NOW,
                Request.Terms.standard().withMinutes(120).withNeedsHands(true).withPreemptible(false));
        assertThat(request.requirements().size()).isEqualTo(2);
        assertThat(request.minutes()).isEqualTo(120);
        assertThat(request.needsHands()).isTrue();
        assertThat(request.preemptible()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"0, 60", "10, 60", "5, 0"})
    void aNonsenseRequestIsRefused(int priority, int minutes) {
        Request.Terms terms = Request.Terms.standard().withPriority(priority).withMinutes(minutes);
        assertThatThrownBy(() -> queue().submit("team-a", "one", wanted(), NOW, terms))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aRequestWithoutAPurposeIsRefused() {
        assertThatThrownBy(() -> queue().submit("team-a", "  ", wanted(), NOW)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aRequestDescribesItself() {
        Request request = queue().submit(
                        "team-a",
                        "capture run",
                        wanted(),
                        NOW,
                        Request.Terms.standard().withPriority(3));
        assertThat(request.describe()).isEqualTo("req-00001 team-a priority 3 for capture run");
    }
}
