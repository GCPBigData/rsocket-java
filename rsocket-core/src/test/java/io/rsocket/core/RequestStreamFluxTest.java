package io.rsocket.core;

import static io.rsocket.core.RequestStreamFlux.FLAG_REASSEMBLY;
import static io.rsocket.core.RequestStreamFlux.MASK_REQUESTED;
import static io.rsocket.core.RequestStreamFlux.MASK_REQUESTED_MAX;
import static io.rsocket.core.RequestStreamFlux.STATE_SUBSCRIBED;
import static io.rsocket.core.RequestStreamFlux.STATE_TERMINATED;
import static io.rsocket.core.RequestStreamFlux.STATE_UNSUBSCRIBED;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;
import io.netty.util.IllegalReferenceCountException;
import io.netty.util.collection.IntObjectMap;
import io.rsocket.FrameAssert;
import io.rsocket.Payload;
import io.rsocket.exceptions.ApplicationErrorException;
import io.rsocket.frame.FrameLengthFlyweight;
import io.rsocket.frame.FrameType;
import io.rsocket.frame.decoder.PayloadDecoder;
import io.rsocket.internal.SynchronizedIntObjectHashMap;
import io.rsocket.internal.UnboundedProcessor;
import io.rsocket.internal.subscriber.AssertSubscriber;
import io.rsocket.util.ByteBufPayload;
import io.rsocket.util.EmptyPayload;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.Exceptions;
import reactor.core.Scannable;
import reactor.test.StepVerifier;
import reactor.test.util.RaceTestUtils;

public class RequestStreamFluxTest {

  @BeforeAll
  public static void setUp() {
    StepVerifier.setDefaultTimeout(Duration.ofSeconds(2));
  }

  /*
   * +-------------------------------+
   * |      General Test Cases       |
   * +-------------------------------+
   */

  /**
   * State Machine check. Ensure migration from
   *
   * <pre>
   * UNSUBSCRIBED -> SUBSCRIBED
   * SUBSCRIBED -> REQUESTED(1) -> REQUESTED(0)
   * REQUESTED(0) -> REQUESTED(1) -> REQUESTED(0)
   * REQUESTED(0) -> REQUESTED(MAX)
   * REQUESTED(MAX) -> REQUESTED(MAX) && REASSEMBLY (extra flag enabled which indicates reassembly)
   * REQUESTED(MAX) && REASSEMBLY -> TERMINATED
   * </pre>
   */
  @Test
  public void requestNFrameShouldBeSentOnSubscriptionAndThenSeparately() {
    final UnboundedProcessor<ByteBuf> sender = new UnboundedProcessor<>();
    final Payload payload = ByteBufPayload.create("testData", "testMetadata");
    final IntObjectMap<Reassemble<?>> activeStreams = new SynchronizedIntObjectHashMap<>();

    final RequestStreamFlux requestStreamFlux =
        new RequestStreamFlux(
            ByteBufAllocator.DEFAULT,
            payload,
            0,
            TestStateAware.empty(),
            StreamIdSupplier.clientSupplier(),
            activeStreams,
            sender,
            PayloadDecoder.ZERO_COPY);

    // state machine check
    Assertions.assertThat(requestStreamFlux.requested)
        .isEqualTo(RequestStreamFlux.STATE_UNSUBSCRIBED);
    Assertions.assertThat(activeStreams).isEmpty();

    final AssertSubscriber<Payload> assertSubscriber =
        requestStreamFlux.subscribeWith(AssertSubscriber.create(0));
    Assertions.assertThat(payload.refCnt()).isOne();
    Assertions.assertThat(activeStreams).isEmpty();
    // state machine check
    Assertions.assertThat(requestStreamFlux.requested)
        .isEqualTo(RequestStreamFlux.STATE_SUBSCRIBED);

    assertSubscriber.request(1);

    Assertions.assertThat(payload.refCnt()).isZero();
    Assertions.assertThat(activeStreams).containsEntry(1, requestStreamFlux);

    // state machine check
    Assertions.assertThat(requestStreamFlux.requested).isZero();

    final ByteBuf frame = sender.poll();
    FrameAssert.assertThat(frame)
        .isNotNull()
        .hasPayloadSize(
            "testData".getBytes(CharsetUtil.UTF_8).length
                + "testMetadata".getBytes(CharsetUtil.UTF_8).length)
        .hasMetadata("testMetadata")
        .hasData("testData")
        .hasNoFragmentsFollow()
        .hasRequestN(1)
        .typeOf(FrameType.REQUEST_STREAM)
        .hasClientSideStreamId()
        .hasStreamId(1);

    Assertions.assertThat(sender.isEmpty()).isTrue();

    Assertions.assertThat(frame.release()).isTrue();
    Assertions.assertThat(frame.refCnt()).isZero();

    assertSubscriber.request(1);
    final ByteBuf requestNFrame = sender.poll();
    FrameAssert.assertThat(requestNFrame)
        .isNotNull()
        .hasRequestN(1)
        .typeOf(FrameType.REQUEST_N)
        .hasClientSideStreamId()
        .hasStreamId(1);

    Assertions.assertThat(sender.isEmpty()).isTrue();

    Assertions.assertThat(requestNFrame.release()).isTrue();
    Assertions.assertThat(requestNFrame.refCnt()).isZero();

    // state machine check. Request N Frame should sent so request field should be 0
    Assertions.assertThat(requestStreamFlux.requested).isZero();

    assertSubscriber.request(Long.MAX_VALUE);
    final ByteBuf requestMaxNFrame = sender.poll();
    FrameAssert.assertThat(requestMaxNFrame)
        .isNotNull()
        .hasRequestN(Integer.MAX_VALUE)
        .typeOf(FrameType.REQUEST_N)
        .hasClientSideStreamId()
        .hasStreamId(1);

    Assertions.assertThat(sender.isEmpty()).isTrue();

    Assertions.assertThat(requestMaxNFrame.release()).isTrue();
    Assertions.assertThat(requestMaxNFrame.refCnt()).isZero();

    // state machine check
    Assertions.assertThat(requestStreamFlux.requested)
        .isEqualTo(RequestStreamFlux.MASK_REQUESTED_MAX);

    assertSubscriber.request(6);
    Assertions.assertThat(sender.isEmpty()).isTrue();

    // state machine check
    Assertions.assertThat(requestStreamFlux.requested)
        .isEqualTo(RequestStreamFlux.MASK_REQUESTED_MAX);

    // no intent to check reassembly correctness here, just to ensure that state is correctly formed
    requestStreamFlux.reassemble(Unpooled.EMPTY_BUFFER, true, false);

    // state machine check
    Assertions.assertThat(requestStreamFlux.requested)
        .matches(
            state ->
                (state & RequestStreamFlux.MASK_REQUESTED_MAX)
                    == RequestStreamFlux.MASK_REQUESTED_MAX)
        .isEqualTo(RequestStreamFlux.MASK_REQUESTED_MAX | FLAG_REASSEMBLY);

    requestStreamFlux.onNext(EmptyPayload.INSTANCE);

    requestStreamFlux.onComplete();
    assertSubscriber.assertValues(EmptyPayload.INSTANCE).assertComplete();

    Assertions.assertThat(payload.refCnt()).isZero();
    Assertions.assertThat(activeStreams).isEmpty();

    Assertions.assertThat(sender.isEmpty()).isTrue();

    // state machine check
    Assertions.assertThat(requestStreamFlux.requested).isEqualTo(STATE_TERMINATED);
  }

  /**
   * State Machine check. Ensure migration from
   *
   * <pre>
   * UNSUBSCRIBED -> SUBSCRIBED
   * SUBSCRIBED -> REQUESTED(MAX)
   * REQUESTED(MAX) -> TERMINATED
   * </pre>
   */
  @Test
  public void requestNFrameShouldBeSentExactlyOnceIfItIsMaxAllowed() {
    final UnboundedProcessor<ByteBuf> sender = new UnboundedProcessor<>();
    final Payload payload = ByteBufPayload.create("testData", "testMetadata");
    final IntObjectMap<Reassemble<?>> activeStreams = new SynchronizedIntObjectHashMap<>();

    final RequestStreamFlux requestStreamFlux =
        new RequestStreamFlux(
            ByteBufAllocator.DEFAULT,
            payload,
            0,
            TestStateAware.empty(),
            StreamIdSupplier.clientSupplier(),
            activeStreams,
            sender,
            PayloadDecoder.ZERO_COPY);

    // state machine check
    Assertions.assertThat(requestStreamFlux.requested)
        .isEqualTo(RequestStreamFlux.STATE_UNSUBSCRIBED);
    Assertions.assertThat(activeStreams).isEmpty();

    final AssertSubscriber<Payload> assertSubscriber =
        requestStreamFlux.subscribeWith(AssertSubscriber.create(0));
    Assertions.assertThat(payload.refCnt()).isOne();
    Assertions.assertThat(activeStreams).isEmpty();

    // state machine check
    Assertions.assertThat(requestStreamFlux.requested)
        .isEqualTo(RequestStreamFlux.STATE_SUBSCRIBED);

    assertSubscriber.request(Long.MAX_VALUE / 2 + 1);

    // state machine check
    Assertions.assertThat(requestStreamFlux.requested)
        .isEqualTo(RequestStreamFlux.MASK_REQUESTED_MAX);

    Assertions.assertThat(payload.refCnt()).isZero();
    Assertions.assertThat(activeStreams).containsEntry(1, requestStreamFlux);

    final ByteBuf frame = sender.poll();
    FrameAssert.assertThat(frame)
        .isNotNull()
        .hasPayloadSize(
            "testData".getBytes(CharsetUtil.UTF_8).length
                + "testMetadata".getBytes(CharsetUtil.UTF_8).length)
        .hasMetadata("testMetadata")
        .hasData("testData")
        .hasNoFragmentsFollow()
        .hasRequestN(Integer.MAX_VALUE)
        .typeOf(FrameType.REQUEST_STREAM)
        .hasClientSideStreamId()
        .hasStreamId(1);

    Assertions.assertThat(sender.isEmpty()).isTrue();

    Assertions.assertThat(frame.release()).isTrue();
    Assertions.assertThat(frame.refCnt()).isZero();

    assertSubscriber.request(1);
    Assertions.assertThat(sender.isEmpty()).isTrue();

    // state machine check
    Assertions.assertThat(requestStreamFlux.requested)
        .isEqualTo(RequestStreamFlux.MASK_REQUESTED_MAX);

    requestStreamFlux.onNext(EmptyPayload.INSTANCE);
    requestStreamFlux.onComplete();

    assertSubscriber.assertValues(EmptyPayload.INSTANCE).assertComplete();

    Assertions.assertThat(payload.refCnt()).isZero();
    Assertions.assertThat(activeStreams).isEmpty();
    // state machine check
    Assertions.assertThat(requestStreamFlux.requested).isEqualTo(STATE_TERMINATED);
    Assertions.assertThat(sender.isEmpty()).isTrue();
  }

  /**
   * State Machine check. Ensure migration from
   *
   * <pre>
   * UNSUBSCRIBED -> SUBSCRIBED
   * SUBSCRIBED -> REQUESTED(1) -> REQUESTED(0)
   * </pre>
   *
   * And then for the following cases:
   *
   * <pre>
   * [0]: REQUESTED(0) -> REQUESTED(MAX) (with onNext and few extra request(1) which should not affect state anyhow and should not sent any extra frames)
   *      REQUESTED(MAX) -> TERMINATED
   *
   * [1]: REQUESTED(0) -> REQUESTED(MAX) (with onComplete rightaway)
   *      REQUESTED(MAX) -> TERMINATED
   *
   * [2]: REQUESTED(0) -> REQUESTED(MAX) (with onError rightaway)
   *      REQUESTED(MAX) -> TERMINATED
   *
   * [3]: REQUESTED(0) -> REASSEMBLY
   *      REASSEMBLY -> REASSEMBLY && REQUESTED(MAX)
   *      REASSEMBLY && REQUESTED(MAX) -> REQUESTED(MAX)
   *      REQUESTED(MAX) -> TERMINATED
   *
   * [4]: REQUESTED(0) -> REQUESTED(MAX)
   *      REQUESTED(MAX) -> REASSEMBLY && REQUESTED(MAX)
   *      REASSEMBLY && REQUESTED(MAX) -> TERMINATED (because of cancel() invocation)
   * </pre>
   */
  @ParameterizedTest
  @MethodSource("frameShouldBeSentOnFirstRequestResponses")
  public void frameShouldBeSentOnFirstRequest(
      BiFunction<RequestStreamFlux, StepVerifier.Step<Payload>, StepVerifier> transformer) {
    final UnboundedProcessor<ByteBuf> sender = new UnboundedProcessor<>();
    final Payload payload = ByteBufPayload.create("testData", "testMetadata");
    final IntObjectMap<Reassemble<?>> activeStreams = new SynchronizedIntObjectHashMap<>();

    final RequestStreamFlux requestStreamFlux =
        new RequestStreamFlux(
            ByteBufAllocator.DEFAULT,
            payload,
            0,
            TestStateAware.empty(),
            StreamIdSupplier.clientSupplier(),
            activeStreams,
            sender,
            PayloadDecoder.ZERO_COPY);

    // state machine check
    Assertions.assertThat(requestStreamFlux.requested)
        .isEqualTo(RequestStreamFlux.STATE_UNSUBSCRIBED);
    Assertions.assertThat(activeStreams).isEmpty();

    transformer
        .apply(
            requestStreamFlux,
            StepVerifier.create(requestStreamFlux, 0)
                .expectSubscription()
                .then(
                    () ->
                        // state machine check
                        Assertions.assertThat(requestStreamFlux.requested)
                            .isEqualTo(RequestStreamFlux.STATE_SUBSCRIBED))
                .then(() -> Assertions.assertThat(payload.refCnt()).isOne())
                .then(() -> Assertions.assertThat(activeStreams).isEmpty())
                .thenRequest(1)
                .then(
                    () ->
                        // state machine check
                        Assertions.assertThat(requestStreamFlux.requested).isEqualTo(0))
                .then(() -> Assertions.assertThat(payload.refCnt()).isZero())
                .then(
                    () -> Assertions.assertThat(activeStreams).containsEntry(1, requestStreamFlux)))
        .verify();

    Assertions.assertThat(payload.refCnt()).isZero();
    // should not add anything to map
    Assertions.assertThat(activeStreams).isEmpty();

    final ByteBuf frame = sender.poll();
    FrameAssert.assertThat(frame)
        .isNotNull()
        .hasPayloadSize(
            "testData".getBytes(CharsetUtil.UTF_8).length
                + "testMetadata".getBytes(CharsetUtil.UTF_8).length)
        .hasMetadata("testMetadata")
        .hasData("testData")
        .hasNoFragmentsFollow()
        .hasRequestN(1)
        .typeOf(FrameType.REQUEST_STREAM)
        .hasClientSideStreamId()
        .hasStreamId(1);

    Assertions.assertThat(frame.release()).isTrue();
    Assertions.assertThat(frame.refCnt()).isZero();

    final ByteBuf requestNFrame = sender.poll();
    FrameAssert.assertThat(requestNFrame)
        .isNotNull()
        .typeOf(FrameType.REQUEST_N)
        .hasRequestN(Integer.MAX_VALUE)
        .hasClientSideStreamId()
        .hasStreamId(1);
    Assertions.assertThat(requestNFrame.release()).isTrue();
    Assertions.assertThat(requestNFrame.refCnt()).isZero();

    if (!sender.isEmpty()) {
      final ByteBuf cancelFrame = sender.poll();
      FrameAssert.assertThat(cancelFrame)
          .isNotNull()
          .typeOf(FrameType.CANCEL)
          .hasClientSideStreamId()
          .hasStreamId(1);
      Assertions.assertThat(cancelFrame.release()).isTrue();
      Assertions.assertThat(cancelFrame.refCnt()).isZero();
    }
    // state machine check
    Assertions.assertThat(requestStreamFlux.requested).isEqualTo(STATE_TERMINATED);
    Assertions.assertThat(sender.isEmpty()).isTrue();
  }

  static Stream<BiFunction<RequestStreamFlux, StepVerifier.Step<Payload>, StepVerifier>>
      frameShouldBeSentOnFirstRequestResponses() {
    return Stream.of(
        (rsf, sv) ->
            sv.then(() -> rsf.onNext(EmptyPayload.INSTANCE))
                .expectNext(EmptyPayload.INSTANCE)
                .thenRequest(Long.MAX_VALUE)
                .then(
                    () ->
                        // state machine check
                        Assertions.assertThat(rsf.requested).isEqualTo(MASK_REQUESTED_MAX))
                .then(() -> rsf.onNext(EmptyPayload.INSTANCE))
                .thenRequest(1L)
                .then(
                    () ->
                        // state machine check
                        Assertions.assertThat(rsf.requested).isEqualTo(MASK_REQUESTED_MAX))
                .expectNext(EmptyPayload.INSTANCE)
                .thenRequest(1L)
                .then(
                    () ->
                        // state machine check
                        Assertions.assertThat(rsf.requested).isEqualTo(MASK_REQUESTED_MAX))
                .then(rsf::onComplete)
                .thenRequest(1L)
                .then(
                    () ->
                        // state machine check
                        Assertions.assertThat(rsf.requested).isEqualTo(STATE_TERMINATED))
                .expectComplete(),
        (rsf, sv) ->
            sv.then(() -> rsf.onNext(EmptyPayload.INSTANCE))
                .expectNext(EmptyPayload.INSTANCE)
                .thenRequest(Long.MAX_VALUE)
                .then(
                    () ->
                        // state machine check
                        Assertions.assertThat(rsf.requested).isEqualTo(MASK_REQUESTED_MAX))
                .then(rsf::onComplete)
                .thenRequest(1L)
                .then(
                    () ->
                        // state machine check
                        Assertions.assertThat(rsf.requested).isEqualTo(STATE_TERMINATED))
                .expectComplete(),
        (rsf, sv) ->
            sv.then(() -> rsf.onNext(EmptyPayload.INSTANCE))
                .expectNext(EmptyPayload.INSTANCE)
                .thenRequest(Long.MAX_VALUE)
                .then(
                    () ->
                        // state machine check
                        Assertions.assertThat(rsf.requested).isEqualTo(MASK_REQUESTED_MAX))
                .then(() -> rsf.onError(new ApplicationErrorException("test")))
                .then(
                    () ->
                        // state machine check
                        Assertions.assertThat(rsf.requested).isEqualTo(STATE_TERMINATED))
                .thenRequest(1L)
                .thenRequest(1L)
                .expectErrorSatisfies(
                    t ->
                        Assertions.assertThat(t)
                            .hasMessage("test")
                            .isInstanceOf(ApplicationErrorException.class)),
        (rsf, sv) -> {
          final byte[] metadata = new byte[65];
          final byte[] data = new byte[129];
          ThreadLocalRandom.current().nextBytes(metadata);
          ThreadLocalRandom.current().nextBytes(data);

          final Payload payload = ByteBufPayload.create(data, metadata);
          final Payload payload2 = ByteBufPayload.create(data, metadata);

          return sv.then(
                  () -> {
                    final ByteBuf followingFrame =
                        FragmentationUtils.encodeFirstFragment(
                            ByteBufAllocator.DEFAULT,
                            64,
                            FrameType.NEXT,
                            1,
                            payload.metadata(),
                            payload.data());
                    rsf.reassemble(followingFrame, true, false);
                    followingFrame.release();
                  })
              .then(
                  () ->
                      // state machine check
                      Assertions.assertThat(rsf.requested).isEqualTo(FLAG_REASSEMBLY))
              .then(
                  () -> {
                    final ByteBuf followingFrame =
                        FragmentationUtils.encodeFollowsFragment(
                            ByteBufAllocator.DEFAULT,
                            64,
                            1,
                            false,
                            payload.metadata(),
                            payload.data());
                    rsf.reassemble(followingFrame, true, false);
                    followingFrame.release();
                  })
              .then(
                  () ->
                      // state machine check
                      Assertions.assertThat(rsf.requested).isEqualTo(FLAG_REASSEMBLY))
              .then(
                  () -> {
                    final ByteBuf followingFrame =
                        FragmentationUtils.encodeFollowsFragment(
                            ByteBufAllocator.DEFAULT,
                            64,
                            1,
                            false,
                            payload.metadata(),
                            payload.data());
                    rsf.reassemble(followingFrame, true, false);
                    followingFrame.release();
                  })
              .then(
                  () ->
                      // state machine check
                      Assertions.assertThat(rsf.requested).isEqualTo(FLAG_REASSEMBLY))
              .thenRequest(Long.MAX_VALUE)
              .then(
                  () ->
                      // state machine check
                      Assertions.assertThat(rsf.requested)
                          .isEqualTo(FLAG_REASSEMBLY | MASK_REQUESTED_MAX))
              .then(
                  () -> {
                    final ByteBuf followingFrame =
                        FragmentationUtils.encodeFollowsFragment(
                            ByteBufAllocator.DEFAULT,
                            64,
                            1,
                            false,
                            payload.metadata(),
                            payload.data());
                    rsf.reassemble(followingFrame, false, false);
                    followingFrame.release();
                  })
              .then(
                  () ->
                      // state machine check
                      Assertions.assertThat(rsf.requested).isEqualTo(MASK_REQUESTED_MAX))
              .assertNext(
                  p -> {
                    Assertions.assertThat(p.data()).isEqualTo(Unpooled.wrappedBuffer(data));

                    Assertions.assertThat(p.metadata()).isEqualTo(Unpooled.wrappedBuffer(metadata));
                    Assertions.assertThat(p.release()).isTrue();
                    Assertions.assertThat(p.refCnt()).isZero();
                  })
              .then(payload::release)
              .then(() -> rsf.onNext(payload2))
              .thenRequest(1)
              .then(
                  () ->
                      // state machine check
                      Assertions.assertThat(rsf.requested).isEqualTo(MASK_REQUESTED_MAX))
              .assertNext(
                  p -> {
                    Assertions.assertThat(p.data()).isEqualTo(Unpooled.wrappedBuffer(data));

                    Assertions.assertThat(p.metadata()).isEqualTo(Unpooled.wrappedBuffer(metadata));
                    Assertions.assertThat(p.release()).isTrue();
                    Assertions.assertThat(p.refCnt()).isZero();
                  })
              .thenRequest(1)
              .then(
                  () ->
                      // state machine check
                      Assertions.assertThat(rsf.requested).isEqualTo(MASK_REQUESTED_MAX))
              .then(rsf::onComplete)
              .then(
                  () ->
                      // state machine check
                      Assertions.assertThat(rsf.requested).isEqualTo(STATE_TERMINATED))
              .expectComplete();
        },
        (rsf, sv) -> {
          final byte[] metadata = new byte[65];
          final byte[] data = new byte[129];
          ThreadLocalRandom.current().nextBytes(metadata);
          ThreadLocalRandom.current().nextBytes(data);

          final Payload payload0 = ByteBufPayload.create(data, metadata);
          final Payload payload = ByteBufPayload.create(data, metadata);

          ByteBuf[] fragments =
              new ByteBuf[] {
                FragmentationUtils.encodeFirstFragment(
                    ByteBufAllocator.DEFAULT,
                    64,
                    FrameType.NEXT,
                    1,
                    payload.metadata(),
                    payload.data()),
                FragmentationUtils.encodeFollowsFragment(
                    ByteBufAllocator.DEFAULT, 64, 1, false, payload.metadata(), payload.data()),
                FragmentationUtils.encodeFollowsFragment(
                    ByteBufAllocator.DEFAULT, 64, 1, false, payload.metadata(), payload.data())
              };

          final StepVerifier stepVerifier =
              sv.then(() -> rsf.onNext(payload0))
                  .assertNext(
                      p -> {
                        Assertions.assertThat(p.data()).isEqualTo(Unpooled.wrappedBuffer(data));

                        Assertions.assertThat(p.metadata())
                            .isEqualTo(Unpooled.wrappedBuffer(metadata));
                        Assertions.assertThat(p.release()).isTrue();
                        Assertions.assertThat(p.refCnt()).isZero();
                      })
                  .thenRequest(Long.MAX_VALUE)
                  .then(
                      () ->
                          // state machine check
                          Assertions.assertThat(rsf.requested).isEqualTo(MASK_REQUESTED_MAX))
                  .then(
                      () -> {
                        rsf.reassemble(fragments[0], true, false);
                        fragments[0].release();
                      })
                  .then(
                      () ->
                          // state machine check
                          Assertions.assertThat(rsf.requested)
                              .isEqualTo(MASK_REQUESTED_MAX | FLAG_REASSEMBLY))
                  .then(
                      () -> {
                        rsf.reassemble(fragments[1], true, false);
                        fragments[1].release();
                      })
                  .then(
                      () ->
                          // state machine check
                          Assertions.assertThat(rsf.requested)
                              .isEqualTo(MASK_REQUESTED_MAX | FLAG_REASSEMBLY))
                  .then(
                      () -> {
                        rsf.reassemble(fragments[2], true, false);
                        fragments[2].release();
                      })
                  .then(
                      () ->
                          // state machine check
                          Assertions.assertThat(rsf.requested)
                              .isEqualTo(MASK_REQUESTED_MAX | FLAG_REASSEMBLY))
                  .thenRequest(1)
                  .then(
                      () ->
                          // state machine check
                          Assertions.assertThat(rsf.requested)
                              .isEqualTo(MASK_REQUESTED_MAX | FLAG_REASSEMBLY))
                  .thenRequest(1)
                  .then(
                      () ->
                          // state machine check
                          Assertions.assertThat(rsf.requested)
                              .isEqualTo(MASK_REQUESTED_MAX | FLAG_REASSEMBLY))
                  .then(payload::release)
                  .thenCancel()
                  .verifyLater();

          stepVerifier.verify();
          // state machine check
          Assertions.assertThat(rsf.requested).isEqualTo(STATE_TERMINATED);

          Assertions.assertThat(fragments).allMatch(bb -> bb.refCnt() == 0);

          return stepVerifier;
        });
  }

  /**
   * State Machine check with fragmentation of the first payload. Ensure migration from
   *
   * <pre>
   * UNSUBSCRIBED -> SUBSCRIBED
   * SUBSCRIBED -> REQUESTED(1) -> REQUESTED(0)
   * </pre>
   *
   * And then for the following cases:
   *
   * <pre>
   * [0]: REQUESTED(0) -> REQUESTED(MAX) (with onNext and few extra request(1) which should not affect state anyhow and should not sent any extra frames)
   *      REQUESTED(MAX) -> TERMINATED
   *
   * [1]: REQUESTED(0) -> REQUESTED(MAX) (with onComplete rightaway)
   *      REQUESTED(MAX) -> TERMINATED
   *
   * [2]: REQUESTED(0) -> REQUESTED(MAX) (with onError rightaway)
   *      REQUESTED(MAX) -> TERMINATED
   *
   * [3]: REQUESTED(0) -> REASSEMBLY
   *      REASSEMBLY -> REASSEMBLY && REQUESTED(MAX)
   *      REASSEMBLY && REQUESTED(MAX) -> REQUESTED(MAX)
   *      REQUESTED(MAX) -> TERMINATED
   *
   * [4]: REQUESTED(0) -> REQUESTED(MAX)
   *      REQUESTED(MAX) -> REASSEMBLY && REQUESTED(MAX)
   *      REASSEMBLY && REQUESTED(MAX) -> TERMINATED (because of cancel() invocation)
   * </pre>
   */
  @ParameterizedTest
  @MethodSource("frameShouldBeSentOnFirstRequestResponses")
  public void frameFragmentsShouldBeSentOnFirstRequest(
      BiFunction<RequestStreamFlux, StepVerifier.Step<Payload>, StepVerifier> transformer) {
    final UnboundedProcessor<ByteBuf> sender = new UnboundedProcessor<>();

    final byte[] metadata = new byte[65];
    final byte[] data = new byte[129];
    ThreadLocalRandom.current().nextBytes(metadata);
    ThreadLocalRandom.current().nextBytes(data);

    final Payload payload = ByteBufPayload.create(data, metadata);
    final IntObjectMap<Reassemble<?>> activeStreams = new SynchronizedIntObjectHashMap<>();

    final RequestStreamFlux requestStreamFlux =
        new RequestStreamFlux(
            ByteBufAllocator.DEFAULT,
            payload,
            64,
            TestStateAware.empty(),
            StreamIdSupplier.clientSupplier(),
            activeStreams,
            sender,
            PayloadDecoder.ZERO_COPY);

    // state machine check
    Assertions.assertThat(requestStreamFlux.requested).isEqualTo(STATE_UNSUBSCRIBED);
    Assertions.assertThat(activeStreams).isEmpty();

    transformer
        .apply(
            requestStreamFlux,
            StepVerifier.create(requestStreamFlux, 0)
                .expectSubscription()
                .then(() -> Assertions.assertThat(payload.refCnt()).isOne())
                .then(() -> Assertions.assertThat(activeStreams).isEmpty())
                .thenRequest(1)
                .then(() -> Assertions.assertThat(payload.refCnt()).isZero())
                .then(
                    () -> Assertions.assertThat(activeStreams).containsEntry(1, requestStreamFlux)))
        .verify();

    // should not add anything to map
    Assertions.assertThat(activeStreams).isEmpty();

    Assertions.assertThat(payload.refCnt()).isZero();

    final ByteBuf frameFragment1 = sender.poll();
    FrameAssert.assertThat(frameFragment1)
        .isNotNull()
        .hasPayloadSize(
            51) // 64 - 3 (frame headers) - 3 (encoded metadata length) - 3 frame length - 4
        // InitialRequestN size
        .hasMetadata(Arrays.copyOf(metadata, 51))
        .hasData(Unpooled.EMPTY_BUFFER)
        .hasFragmentsFollow()
        .typeOf(FrameType.REQUEST_STREAM)
        .hasClientSideStreamId()
        .hasStreamId(1);

    Assertions.assertThat(frameFragment1.release()).isTrue();
    Assertions.assertThat(frameFragment1.refCnt()).isZero();

    final ByteBuf frameFragment2 = sender.poll();
    FrameAssert.assertThat(frameFragment2)
        .isNotNull()
        .hasPayloadSize(55) // 64 - 3 (frame headers) - 3 (encoded metadata length) - 3 frame length
        .hasMetadata(Arrays.copyOfRange(metadata, 51, 65))
        .hasData(Arrays.copyOf(data, 41))
        .hasFragmentsFollow()
        .typeOf(FrameType.NEXT)
        .hasClientSideStreamId()
        .hasStreamId(1);

    Assertions.assertThat(frameFragment2.release()).isTrue();
    Assertions.assertThat(frameFragment2.refCnt()).isZero();

    final ByteBuf frameFragment3 = sender.poll();
    FrameAssert.assertThat(frameFragment3)
        .isNotNull()
        .hasPayloadSize(58) // 64 - 3 (frame headers) - 3 frame length (no metadata - no length)
        .hasNoMetadata()
        .hasData(Arrays.copyOfRange(data, 41, 99))
        .hasFragmentsFollow()
        .typeOf(FrameType.NEXT)
        .hasClientSideStreamId()
        .hasStreamId(1);

    Assertions.assertThat(frameFragment3.release()).isTrue();
    Assertions.assertThat(frameFragment3.refCnt()).isZero();

    final ByteBuf frameFragment4 = sender.poll();
    FrameAssert.assertThat(frameFragment4)
        .isNotNull()
        .hasPayloadSize(30)
        .hasNoMetadata()
        .hasData(Arrays.copyOfRange(data, 99, 129))
        .hasNoFragmentsFollow()
        .typeOf(FrameType.NEXT)
        .hasClientSideStreamId()
        .hasStreamId(1);

    Assertions.assertThat(frameFragment4.release()).isTrue();
    Assertions.assertThat(frameFragment4.refCnt()).isZero();

    final ByteBuf requestNFrame = sender.poll();
    FrameAssert.assertThat(requestNFrame)
        .isNotNull()
        .typeOf(FrameType.REQUEST_N)
        .hasRequestN(Integer.MAX_VALUE)
        .hasClientSideStreamId()
        .hasStreamId(1);
    Assertions.assertThat(requestNFrame.release()).isTrue();
    Assertions.assertThat(requestNFrame.refCnt()).isZero();

    if (!sender.isEmpty()) {
      FrameAssert.assertThat(sender.poll())
          .isNotNull()
          .typeOf(FrameType.CANCEL)
          .hasClientSideStreamId()
          .hasStreamId(1);
    }
    Assertions.assertThat(sender).isEmpty();
    // state machine check
    Assertions.assertThat(requestStreamFlux.requested).isEqualTo(STATE_TERMINATED);
  }

  /**
   * Case which ensures that if Payload has incorrect refCnt, the flux ends up with an appropriate
   * error
   */
  @ParameterizedTest
  @MethodSource("shouldErrorOnIncorrectRefCntInGivenPayloadSource")
  public void shouldErrorOnIncorrectRefCntInGivenPayload(Consumer<RequestStreamFlux> monoConsumer) {
    final UnboundedProcessor<ByteBuf> sender = new UnboundedProcessor<>();
    final IntObjectMap<Reassemble<?>> activeStreams = new SynchronizedIntObjectHashMap<>();
    final Payload payload = ByteBufPayload.create("");
    payload.release();

    final RequestStreamFlux requestStreamFlux =
        new RequestStreamFlux(
            ByteBufAllocator.DEFAULT,
            payload,
            0,
            TestStateAware.empty(),
            StreamIdSupplier.clientSupplier(),
            activeStreams,
            sender,
            PayloadDecoder.ZERO_COPY);

    // state machine check
    Assertions.assertThat(requestStreamFlux.requested).isEqualTo(STATE_UNSUBSCRIBED);
    Assertions.assertThat(activeStreams).isEmpty();

    monoConsumer.accept(requestStreamFlux);

    Assertions.assertThat(activeStreams).isEmpty();
    Assertions.assertThat(sender).isEmpty();
    // state machine check
    Assertions.assertThat(requestStreamFlux.requested).isEqualTo(STATE_TERMINATED);
  }

  static Stream<Consumer<RequestStreamFlux>> shouldErrorOnIncorrectRefCntInGivenPayloadSource() {
    return Stream.of(
        (s) ->
            StepVerifier.create(s)
                .expectSubscription()
                .expectError(IllegalReferenceCountException.class)
                .verify(),
        requestStreamFlux ->
            Assertions.assertThatThrownBy(requestStreamFlux::blockLast)
                .isInstanceOf(IllegalReferenceCountException.class));
  }

  /**
   * Ensures that if Payload is release right after the subscription, the first request will exponse
   * the error immediatelly and no frame will be sent to the remote party
   */
  @Test
  public void shouldErrorOnIncorrectRefCntInGivenPayloadLatePhase() {
    final UnboundedProcessor<ByteBuf> sender = new UnboundedProcessor<>();
    final IntObjectMap<Reassemble<?>> activeStreams = new SynchronizedIntObjectHashMap<>();
    final Payload payload = ByteBufPayload.create("");

    final RequestStreamFlux requestStreamFlux =
        new RequestStreamFlux(
            ByteBufAllocator.DEFAULT,
            payload,
            0,
            TestStateAware.empty(),
            StreamIdSupplier.clientSupplier(),
            activeStreams,
            sender,
            PayloadDecoder.ZERO_COPY);

    // state machine check
    Assertions.assertThat(requestStreamFlux.requested).isEqualTo(STATE_UNSUBSCRIBED);
    Assertions.assertThat(activeStreams).isEmpty();

    StepVerifier.create(requestStreamFlux, 0)
        .expectSubscription()
        .then(
            () ->
                // state machine check
                Assertions.assertThat(requestStreamFlux.requested).isEqualTo(STATE_SUBSCRIBED))
        .then(payload::release)
        .thenRequest(1)
        .then(
            () ->
                // state machine check
                Assertions.assertThat(requestStreamFlux.requested).isEqualTo(STATE_TERMINATED))
        .expectError(IllegalReferenceCountException.class)
        .verify();

    Assertions.assertThat(activeStreams).isEmpty();
    Assertions.assertThat(sender).isEmpty();

    // state machine check
    Assertions.assertThat(requestStreamFlux.requested).isEqualTo(STATE_TERMINATED);
  }

  /**
   * Ensures that if Payload is release right after the subscription, the first request will expose
   * the error immediately and no frame will be sent to the remote party
   */
  @Test
  public void shouldErrorOnIncorrectRefCntInGivenPayloadLatePhaseWithFragmentation() {
    final UnboundedProcessor<ByteBuf> sender = new UnboundedProcessor<>();
    final IntObjectMap<Reassemble<?>> activeStreams = new SynchronizedIntObjectHashMap<>();
    final byte[] metadata = new byte[65];
    final byte[] data = new byte[129];
    ThreadLocalRandom.current().nextBytes(metadata);
    ThreadLocalRandom.current().nextBytes(data);

    final Payload payload = ByteBufPayload.create(data, metadata);

    final RequestStreamFlux requestStreamFlux =
        new RequestStreamFlux(
            ByteBufAllocator.DEFAULT,
            payload,
            64,
            TestStateAware.empty(),
            StreamIdSupplier.clientSupplier(),
            activeStreams,
            sender,
            PayloadDecoder.ZERO_COPY);

    // state machine check
    Assertions.assertThat(requestStreamFlux.requested).isEqualTo(STATE_UNSUBSCRIBED);
    Assertions.assertThat(activeStreams).isEmpty();

    StepVerifier.create(requestStreamFlux, 0)
        .expectSubscription()
        .then(
            () ->
                // state machine check
                Assertions.assertThat(requestStreamFlux.requested).isEqualTo(STATE_SUBSCRIBED))
        .then(payload::release)
        .thenRequest(1)
        .then(
            () ->
                // state machine check
                Assertions.assertThat(requestStreamFlux.requested).isEqualTo(STATE_TERMINATED))
        .expectError(IllegalReferenceCountException.class)
        .verify();

    Assertions.assertThat(activeStreams).isEmpty();
    Assertions.assertThat(sender).isEmpty();
    // state machine check
    Assertions.assertThat(requestStreamFlux.requested).isEqualTo(STATE_TERMINATED);
  }

  /**
   * Ensures that if the given payload is exits 16mb size with disabled fragmentation, than the
   * appropriate validation happens and a corresponding error will be propagagted to the subscriber
   */
  @ParameterizedTest
  @MethodSource("shouldErrorIfFragmentExitsAllowanceIfFragmentationDisabledSource")
  public void shouldErrorIfFragmentExitsAllowanceIfFragmentationDisabled(
      Consumer<RequestStreamFlux> monoConsumer) {
    final UnboundedProcessor<ByteBuf> sender = new UnboundedProcessor<>();
    final IntObjectMap<Reassemble<?>> activeStreams = new SynchronizedIntObjectHashMap<>();

    final byte[] metadata = new byte[FrameLengthFlyweight.FRAME_LENGTH_MASK];
    final byte[] data = new byte[FrameLengthFlyweight.FRAME_LENGTH_MASK];
    ThreadLocalRandom.current().nextBytes(metadata);
    ThreadLocalRandom.current().nextBytes(data);

    final Payload payload = ByteBufPayload.create(data, metadata);

    final RequestStreamFlux requestStreamFlux =
        new RequestStreamFlux(
            ByteBufAllocator.DEFAULT,
            payload,
            0,
            TestStateAware.empty(),
            StreamIdSupplier.clientSupplier(),
            activeStreams,
            sender,
            PayloadDecoder.ZERO_COPY);

    // state machine check
    Assertions.assertThat(requestStreamFlux.requested).isEqualTo(STATE_UNSUBSCRIBED);
    Assertions.assertThat(activeStreams).isEmpty();

    monoConsumer.accept(requestStreamFlux);

    Assertions.assertThat(payload.refCnt()).isZero();

    Assertions.assertThat(activeStreams).isEmpty();
    Assertions.assertThat(sender).isEmpty();
    // state machine check
    Assertions.assertThat(requestStreamFlux.requested).isEqualTo(STATE_TERMINATED);
  }

  static Stream<Consumer<RequestStreamFlux>>
      shouldErrorIfFragmentExitsAllowanceIfFragmentationDisabledSource() {
    return Stream.of(
        (s) ->
            StepVerifier.create(s, 0)
                .expectSubscription()
                .then(
                    () ->
                        // state machine check
                        Assertions.assertThat(s.requested).isEqualTo(STATE_TERMINATED))
                .consumeErrorWith(
                    t ->
                        Assertions.assertThat(t)
                            .hasMessage("Too Big Payload size")
                            .isInstanceOf(IllegalArgumentException.class))
                .verify(),
        requestStreamFlux ->
            Assertions.assertThatThrownBy(requestStreamFlux::blockLast)
                .hasMessage("Too Big Payload size")
                .isInstanceOf(IllegalArgumentException.class));
  }

  /**
   * Ensures that the interactions check and respect rsocket availability (such as leasing) and
   * propagate an error to the final subscriber. No frame should be sent. Check should happens
   * exactly on the first request.
   */
  @ParameterizedTest
  @MethodSource("shouldErrorIfNoAvailabilitySource")
  public void shouldErrorIfNoAvailability(Consumer<RequestStreamFlux> monoConsumer) {
    final UnboundedProcessor<ByteBuf> sender = new UnboundedProcessor<>();
    final IntObjectMap<Reassemble<?>> activeStreams = new SynchronizedIntObjectHashMap<>();
    final Payload payload = ByteBufPayload.create("testData", "testMetadata");

    final RequestStreamFlux requestStreamFlux =
        new RequestStreamFlux(
            ByteBufAllocator.DEFAULT,
            payload,
            0,
            TestStateAware.error(new RuntimeException("test")),
            StreamIdSupplier.clientSupplier(),
            activeStreams,
            sender,
            PayloadDecoder.ZERO_COPY);

    // state machine check
    Assertions.assertThat(requestStreamFlux.requested).isEqualTo(STATE_UNSUBSCRIBED);
    Assertions.assertThat(activeStreams).isEmpty();

    monoConsumer.accept(requestStreamFlux);

    Assertions.assertThat(payload.refCnt()).isZero();

    Assertions.assertThat(activeStreams).isEmpty();
  }

  static Stream<Consumer<RequestStreamFlux>> shouldErrorIfNoAvailabilitySource() {
    return Stream.of(
        (s) ->
            StepVerifier.create(s, 0)
                .expectSubscription()
                .then(
                    () ->
                        // state machine check
                        Assertions.assertThat(s.requested).isEqualTo(STATE_SUBSCRIBED))
                .thenRequest(1)
                .then(
                    () ->
                        // state machine check
                        Assertions.assertThat(s.requested).isEqualTo(STATE_TERMINATED))
                .consumeErrorWith(
                    t ->
                        Assertions.assertThat(t)
                            .hasMessage("test")
                            .isInstanceOf(RuntimeException.class))
                .verify(),
        requestStreamFlux ->
            Assertions.assertThatThrownBy(requestStreamFlux::blockLast)
                .hasMessage("test")
                .isInstanceOf(RuntimeException.class));
  }

  /*
   * +--------------------------------+
   * |       Racing Test Cases        |
   * +--------------------------------+
   */

  @Test
  public void shouldSubscribeExactlyOnce1() {
    final UnboundedProcessor<ByteBuf> sender = new UnboundedProcessor<>();

    for (int i = 0; i < 1000; i++) {
      final IntObjectMap<Reassemble<?>> activeStreams = new SynchronizedIntObjectHashMap<>();
      final Payload payload = ByteBufPayload.create("testData", "testMetadata");

      final RequestStreamFlux requestStreamFlux =
          new RequestStreamFlux(
              ByteBufAllocator.DEFAULT,
              payload,
              0,
              TestStateAware.empty(),
              StreamIdSupplier.clientSupplier(),
              activeStreams,
              sender,
              PayloadDecoder.ZERO_COPY);

      Assertions.assertThatThrownBy(
              () ->
                  RaceTestUtils.race(
                      () ->
                          requestStreamFlux.subscribe(
                              null,
                              t -> {
                                throw Exceptions.propagate(t);
                              }),
                      () ->
                          requestStreamFlux.subscribe(
                              null,
                              t -> {
                                throw Exceptions.propagate(t);
                              })))
          .matches(
              t -> {
                if (t instanceof IllegalReferenceCountException) {
                  Assertions.assertThat(t).hasMessage("refCnt: 0");
                } else {
                  Assertions.assertThat(t)
                      .hasMessage("RequestStreamFlux allows only a single Subscriber");
                }
                return true;
              });

      final ByteBuf frame = sender.poll();
      FrameAssert.assertThat(frame)
          .isNotNull()
          .hasPayloadSize(
              "testData".getBytes(CharsetUtil.UTF_8).length
                  + "testMetadata".getBytes(CharsetUtil.UTF_8).length)
          .hasMetadata("testMetadata")
          .hasData("testData")
          .hasNoFragmentsFollow()
          .typeOf(FrameType.REQUEST_STREAM)
          .hasClientSideStreamId()
          .hasStreamId(1);

      Assertions.assertThat(frame.release()).isTrue();
      Assertions.assertThat(frame.refCnt()).isZero();
    }

    Assertions.assertThat(sender.isEmpty()).isTrue();
  }

  @Test
  public void shouldBeNoOpsOnCancel() {
    final UnboundedProcessor<ByteBuf> sender = new UnboundedProcessor<>();
    final IntObjectMap<Reassemble<?>> activeStreams = new SynchronizedIntObjectHashMap<>();
    final Payload payload = ByteBufPayload.create("testData", "testMetadata");

    final RequestStreamFlux requestStreamFlux =
        new RequestStreamFlux(
            ByteBufAllocator.DEFAULT,
            payload,
            0,
            TestStateAware.empty(),
            StreamIdSupplier.clientSupplier(),
            activeStreams,
            sender,
            PayloadDecoder.ZERO_COPY);

    StepVerifier.create(requestStreamFlux, 0)
        .expectSubscription()
        .then(() -> Assertions.assertThat(activeStreams).isEmpty())
        .thenCancel()
        .verify();

    Assertions.assertThat(payload.refCnt()).isZero();

    Assertions.assertThat(activeStreams).isEmpty();
    Assertions.assertThat(sender.isEmpty()).isTrue();
  }

  @Test
  public void shouldHaveNoLeaksOnReassemblyAndCancelRacing() {
    for (int i = 0; i < 1000; i++) {

      final UnboundedProcessor<ByteBuf> sender = new UnboundedProcessor<>();
      final IntObjectMap<Reassemble<?>> activeStreams = new SynchronizedIntObjectHashMap<>();
      final Payload payload = ByteBufPayload.create("testData", "testMetadata");

      final RequestStreamFlux requestStreamFlux =
          new RequestStreamFlux(
              ByteBufAllocator.DEFAULT,
              payload,
              0,
              TestStateAware.empty(),
              StreamIdSupplier.clientSupplier(),
              activeStreams,
              sender,
              PayloadDecoder.ZERO_COPY);

      ByteBuf frame = Unpooled.wrappedBuffer("test".getBytes(CharsetUtil.UTF_8));

      StepVerifier.create(requestStreamFlux).expectSubscription().expectComplete().verifyLater();

      final ByteBuf sentFrame = sender.poll();
      FrameAssert.assertThat(sentFrame)
          .isNotNull()
          .hasPayloadSize(
              "testData".getBytes(CharsetUtil.UTF_8).length
                  + "testMetadata".getBytes(CharsetUtil.UTF_8).length)
          .hasMetadata("testMetadata")
          .hasData("testData")
          .hasNoFragmentsFollow()
          .typeOf(FrameType.REQUEST_STREAM)
          .hasClientSideStreamId()
          .hasStreamId(1);

      Assertions.assertThat(sentFrame.release()).isTrue();
      Assertions.assertThat(sentFrame.refCnt()).isZero();

      RaceTestUtils.race(
          requestStreamFlux::cancel,
          () -> {
            requestStreamFlux.reassemble(frame, true, false);
            frame.release();
          });

      final ByteBuf cancellationFrame = sender.poll();
      FrameAssert.assertThat(cancellationFrame)
          .isNotNull()
          .typeOf(FrameType.CANCEL)
          .hasClientSideStreamId()
          .hasStreamId(1);

      Assertions.assertThat(payload.refCnt()).isZero();
      Assertions.assertThat(frame.refCnt()).isZero();

      Assertions.assertThat(activeStreams).isEmpty();
      Assertions.assertThat(sender.isEmpty()).isTrue();
    }
  }

  @Test
  public void shouldHaveNoLeaksOnNextAndCancelRacing() {
    for (int i = 0; i < 1000; i++) {

      final UnboundedProcessor<ByteBuf> sender = new UnboundedProcessor<>();
      final IntObjectMap<Reassemble<?>> activeStreams = new SynchronizedIntObjectHashMap<>();
      final Payload payload = ByteBufPayload.create("testData", "testMetadata");

      final RequestStreamFlux requestStreamFlux =
          new RequestStreamFlux(
              ByteBufAllocator.DEFAULT,
              payload,
              0,
              TestStateAware.empty(),
              StreamIdSupplier.clientSupplier(),
              activeStreams,
              sender,
              PayloadDecoder.ZERO_COPY);

      Payload response = ByteBufPayload.create("test", "test");

      StepVerifier.create(requestStreamFlux.doOnNext(Payload::release))
          .expectSubscription()
          .expectComplete()
          .verifyLater();

      final ByteBuf sentFrame = sender.poll();
      FrameAssert.assertThat(sentFrame)
          .isNotNull()
          .hasPayloadSize(
              "testData".getBytes(CharsetUtil.UTF_8).length
                  + "testMetadata".getBytes(CharsetUtil.UTF_8).length)
          .hasMetadata("testMetadata")
          .hasData("testData")
          .hasNoFragmentsFollow()
          .typeOf(FrameType.REQUEST_STREAM)
          .hasClientSideStreamId()
          .hasStreamId(1);

      Assertions.assertThat(sentFrame.release()).isTrue();
      Assertions.assertThat(sentFrame.refCnt()).isZero();

      RaceTestUtils.race(requestStreamFlux::cancel, () -> requestStreamFlux.onNext(response));

      Assertions.assertThat(payload.refCnt()).isZero();
      Assertions.assertThat(response.refCnt()).isZero();

      Assertions.assertThat(activeStreams).isEmpty();
      final boolean isEmpty = sender.isEmpty();
      if (!isEmpty) {
        final ByteBuf cancellationFrame = sender.poll();
        FrameAssert.assertThat(cancellationFrame)
            .isNotNull()
            .typeOf(FrameType.CANCEL)
            .hasClientSideStreamId()
            .hasStreamId(1);
      }
      Assertions.assertThat(sender.isEmpty()).isTrue();
    }
  }

  @ParameterizedTest
  @MethodSource("racingCases")
  public void shouldSentRequestStreamFrameOnceInCaseOfRequestRacing(
      Function<RequestStreamFlux, Runnable> cases, long realRequestN, int expectedN) {
    for (int i = 0; i < 1000; i++) {

      final UnboundedProcessor<ByteBuf> sender = new UnboundedProcessor<>();
      final IntObjectMap<Reassemble<?>> activeStreams = new SynchronizedIntObjectHashMap<>();
      final Payload payload = ByteBufPayload.create("testData", "testMetadata");

      final RequestStreamFlux requestStreamFlux =
          new RequestStreamFlux(
              ByteBufAllocator.DEFAULT,
              payload,
              0,
              TestStateAware.empty(),
              StreamIdSupplier.clientSupplier(),
              activeStreams,
              sender,
              PayloadDecoder.ZERO_COPY);

      Payload response = ByteBufPayload.create("test", "test");

      final AssertSubscriber<Payload> assertSubscriber =
          requestStreamFlux.doOnNext(Payload::release).subscribeWith(AssertSubscriber.create(0));

      RaceTestUtils.race(cases.apply(requestStreamFlux), cases.apply(requestStreamFlux));

      final ByteBuf sentFrame = sender.poll();
      FrameAssert.assertThat(sentFrame)
          .isNotNull()
          .hasPayloadSize(
              "testData".getBytes(CharsetUtil.UTF_8).length
                  + "testMetadata".getBytes(CharsetUtil.UTF_8).length)
          .hasMetadata("testMetadata")
          .hasData("testData")
          .hasNoFragmentsFollow()
          .typeOf(FrameType.REQUEST_STREAM)
          .hasClientSideStreamId()
          .hasRequestN(expectedN)
          .hasStreamId(1);

      Assertions.assertThat(sentFrame.release()).isTrue();
      Assertions.assertThat(sentFrame.refCnt()).isZero();

      Assertions.assertThat(payload.refCnt()).isZero();

      if (realRequestN < MASK_REQUESTED) {
        final ByteBuf requestNFrame = sender.poll();
        FrameAssert.assertThat(requestNFrame)
            .isNotNull()
            .typeOf(FrameType.REQUEST_N)
            .hasRequestN(expectedN)
            .hasClientSideStreamId()
            .hasStreamId(1);

        Assertions.assertThat(requestNFrame.release()).isTrue();
        Assertions.assertThat(requestNFrame.refCnt()).isZero();

        Assertions.assertThat(requestStreamFlux.requested).isZero();
      } else {
        Assertions.assertThat(requestStreamFlux.requested).isEqualTo(MASK_REQUESTED_MAX);
      }

      requestStreamFlux.onNext(response);
      Assertions.assertThat(response.refCnt()).isZero();

      requestStreamFlux.onComplete();
      assertSubscriber.assertTerminated();

      Assertions.assertThat(activeStreams).isEmpty();
      Assertions.assertThat(sender.isEmpty()).isTrue();
    }
  }

  @ParameterizedTest
  @MethodSource("racingCases")
  public void shouldBeConsistentInCaseOfRequestRacing(
      Function<RequestStreamFlux, Runnable> cases, long realRequestN, int expectedN) {
    for (int i = 0; i < 1000; i++) {

      final UnboundedProcessor<ByteBuf> sender = new UnboundedProcessor<>();
      final IntObjectMap<Reassemble<?>> activeStreams = new SynchronizedIntObjectHashMap<>();
      final Payload payload = ByteBufPayload.create("testData", "testMetadata");

      final RequestStreamFlux requestStreamFlux =
          new RequestStreamFlux(
              ByteBufAllocator.DEFAULT,
              payload,
              0,
              TestStateAware.empty(),
              StreamIdSupplier.clientSupplier(),
              activeStreams,
              sender,
              PayloadDecoder.ZERO_COPY);

      Payload response = ByteBufPayload.create("test", "test");

      final AssertSubscriber<Payload> assertSubscriber =
          requestStreamFlux.doOnNext(Payload::release).subscribeWith(AssertSubscriber.create(0));

      assertSubscriber.request(1);

      final ByteBuf sentFrame = sender.poll();
      FrameAssert.assertThat(sentFrame)
          .isNotNull()
          .hasPayloadSize(
              "testData".getBytes(CharsetUtil.UTF_8).length
                  + "testMetadata".getBytes(CharsetUtil.UTF_8).length)
          .hasMetadata("testMetadata")
          .hasData("testData")
          .hasNoFragmentsFollow()
          .typeOf(FrameType.REQUEST_STREAM)
          .hasClientSideStreamId()
          .hasStreamId(1);

      Assertions.assertThat(sentFrame.release()).isTrue();
      Assertions.assertThat(sentFrame.refCnt()).isZero();

      Assertions.assertThat(payload.refCnt()).isZero();

      RaceTestUtils.race(cases.apply(requestStreamFlux), cases.apply(requestStreamFlux));

      final ByteBuf requestNFrame1 = sender.poll();
      FrameAssert.assertThat(requestNFrame1)
          .isNotNull()
          .typeOf(FrameType.REQUEST_N)
          .hasRequestN(expectedN)
          .hasClientSideStreamId()
          .hasStreamId(1);

      Assertions.assertThat(requestNFrame1.release()).isTrue();
      Assertions.assertThat(requestNFrame1.refCnt()).isZero();

      if (realRequestN < RequestStreamFlux.MASK_REQUESTED) {
        final ByteBuf requestNFrame2 = sender.poll();
        FrameAssert.assertThat(requestNFrame2)
            .isNotNull()
            .typeOf(FrameType.REQUEST_N)
            .hasRequestN(expectedN)
            .hasClientSideStreamId()
            .hasStreamId(1);

        Assertions.assertThat(requestNFrame2.release()).isTrue();
        Assertions.assertThat(requestNFrame2.refCnt()).isZero();

        Assertions.assertThat(requestStreamFlux.requested).isZero();
      } else {
        Assertions.assertThat(requestStreamFlux.requested).isEqualTo(MASK_REQUESTED_MAX);
      }

      Assertions.assertThat(requestStreamFlux.requested)
          .matches(s -> (s & FLAG_REASSEMBLY) != FLAG_REASSEMBLY);

      requestStreamFlux.onNext(response);
      Assertions.assertThat(response.refCnt()).isZero();

      requestStreamFlux.onComplete();
      assertSubscriber.assertTerminated();

      Assertions.assertThat(activeStreams).isEmpty();
      Assertions.assertThat(sender.isEmpty()).isTrue();
    }
  }

  @ParameterizedTest
  @MethodSource("racingCases")
  public void shouldBeConsistentInCaseOfRequestRacingDuringReassembly(
      Function<RequestStreamFlux, Runnable> cases, long realRequestN, int expectedN) {
    for (int i = 0; i < 1000; i++) {

      final UnboundedProcessor<ByteBuf> sender = new UnboundedProcessor<>();
      final IntObjectMap<Reassemble<?>> activeStreams = new SynchronizedIntObjectHashMap<>();
      final Payload payload = ByteBufPayload.create("testData", "testMetadata");

      final RequestStreamFlux requestStreamFlux =
          new RequestStreamFlux(
              ByteBufAllocator.DEFAULT,
              payload,
              0,
              TestStateAware.empty(),
              StreamIdSupplier.clientSupplier(),
              activeStreams,
              sender,
              PayloadDecoder.ZERO_COPY);

      Payload response = ByteBufPayload.create("test", "test");

      final AssertSubscriber<Payload> assertSubscriber =
          requestStreamFlux.doOnNext(Payload::release).subscribeWith(AssertSubscriber.create(0));

      assertSubscriber.request(1);

      final ByteBuf sentFrame = sender.poll();
      FrameAssert.assertThat(sentFrame)
          .isNotNull()
          .hasPayloadSize(
              "testData".getBytes(CharsetUtil.UTF_8).length
                  + "testMetadata".getBytes(CharsetUtil.UTF_8).length)
          .hasMetadata("testMetadata")
          .hasData("testData")
          .hasNoFragmentsFollow()
          .typeOf(FrameType.REQUEST_STREAM)
          .hasClientSideStreamId()
          .hasStreamId(1);

      Assertions.assertThat(sentFrame.release()).isTrue();
      Assertions.assertThat(sentFrame.refCnt()).isZero();

      Assertions.assertThat(payload.refCnt()).isZero();

      requestStreamFlux.reassemble(Unpooled.EMPTY_BUFFER, true, false);

      RaceTestUtils.race(cases.apply(requestStreamFlux), cases.apply(requestStreamFlux));

      final ByteBuf requestNFrame1 = sender.poll();
      FrameAssert.assertThat(requestNFrame1)
          .isNotNull()
          .typeOf(FrameType.REQUEST_N)
          .hasRequestN(expectedN)
          .hasClientSideStreamId()
          .hasStreamId(1);

      Assertions.assertThat(requestNFrame1.release()).isTrue();
      Assertions.assertThat(requestNFrame1.refCnt()).isZero();

      if (realRequestN < RequestStreamFlux.MASK_REQUESTED) {
        final ByteBuf requestNFrame2 = sender.poll();
        FrameAssert.assertThat(requestNFrame2)
            .isNotNull()
            .typeOf(FrameType.REQUEST_N)
            .hasRequestN(expectedN)
            .hasClientSideStreamId()
            .hasStreamId(1);

        Assertions.assertThat(requestNFrame2.release()).isTrue();
        Assertions.assertThat(requestNFrame2.refCnt()).isZero();
      } else {
        Assertions.assertThat(requestStreamFlux.requested)
            .matches(s -> (s & MASK_REQUESTED_MAX) == MASK_REQUESTED_MAX);
      }

      Assertions.assertThat(requestStreamFlux.requested)
          .matches(s -> (s & FLAG_REASSEMBLY) == FLAG_REASSEMBLY);

      requestStreamFlux.onNext(response);
      Assertions.assertThat(response.refCnt()).isZero();

      requestStreamFlux.onComplete();
      assertSubscriber.assertTerminated();

      Assertions.assertThat(activeStreams).isEmpty();
      Assertions.assertThat(sender.isEmpty()).isTrue();
    }
  }

  @ParameterizedTest
  @MethodSource("racingCases")
  public void shouldBeConsistentInCaseOfRacingOfReassemblyAndRequest(
      Function<RequestStreamFlux, Runnable> cases, long realRequestN, int expectedN) {
    for (int i = 0; i < 1000; i++) {

      final UnboundedProcessor<ByteBuf> sender = new UnboundedProcessor<>();
      final IntObjectMap<Reassemble<?>> activeStreams = new SynchronizedIntObjectHashMap<>();
      final Payload payload = ByteBufPayload.create("testData", "testMetadata");

      final RequestStreamFlux requestStreamFlux =
          new RequestStreamFlux(
              ByteBufAllocator.DEFAULT,
              payload,
              0,
              TestStateAware.empty(),
              StreamIdSupplier.clientSupplier(),
              activeStreams,
              sender,
              PayloadDecoder.ZERO_COPY);

      Payload response = ByteBufPayload.create("test", "test");

      final AssertSubscriber<Payload> assertSubscriber =
          requestStreamFlux.doOnNext(Payload::release).subscribeWith(AssertSubscriber.create(0));

      assertSubscriber.request(1);

      final ByteBuf sentFrame = sender.poll();
      FrameAssert.assertThat(sentFrame)
          .isNotNull()
          .hasPayloadSize(
              "testData".getBytes(CharsetUtil.UTF_8).length
                  + "testMetadata".getBytes(CharsetUtil.UTF_8).length)
          .hasMetadata("testMetadata")
          .hasData("testData")
          .hasNoFragmentsFollow()
          .typeOf(FrameType.REQUEST_STREAM)
          .hasClientSideStreamId()
          .hasStreamId(1);

      Assertions.assertThat(sentFrame.release()).isTrue();
      Assertions.assertThat(sentFrame.refCnt()).isZero();

      Assertions.assertThat(payload.refCnt()).isZero();

      RaceTestUtils.race(
          () -> requestStreamFlux.reassemble(Unpooled.EMPTY_BUFFER, true, false),
          cases.apply(requestStreamFlux));

      final ByteBuf requestNFrame1 = sender.poll();
      FrameAssert.assertThat(requestNFrame1)
          .isNotNull()
          .typeOf(FrameType.REQUEST_N)
          .hasRequestN(expectedN)
          .hasClientSideStreamId()
          .hasStreamId(1);

      Assertions.assertThat(requestNFrame1.release()).isTrue();
      Assertions.assertThat(requestNFrame1.refCnt()).isZero();

      Assertions.assertThat(requestStreamFlux.requested)
          .matches(s -> (s & FLAG_REASSEMBLY) == FLAG_REASSEMBLY);

      requestStreamFlux.onNext(response);
      Assertions.assertThat(response.refCnt()).isZero();

      requestStreamFlux.onComplete();
      assertSubscriber.assertTerminated();

      Assertions.assertThat(activeStreams).isEmpty();
      Assertions.assertThat(sender.isEmpty()).isTrue();
    }
  }

  @ParameterizedTest
  @MethodSource("racingCases")
  public void shouldBeConsistentInCaseOfRacingOfCancellationAndRequest(
      Function<RequestStreamFlux, Runnable> cases, long realRequestN, int expectedN) {
    for (int i = 0; i < 1000; i++) {

      final UnboundedProcessor<ByteBuf> sender = new UnboundedProcessor<>();
      final IntObjectMap<Reassemble<?>> activeStreams = new SynchronizedIntObjectHashMap<>();
      final Payload payload = ByteBufPayload.create("testData", "testMetadata");

      final RequestStreamFlux requestStreamFlux =
          new RequestStreamFlux(
              ByteBufAllocator.DEFAULT,
              payload,
              0,
              TestStateAware.empty(),
              StreamIdSupplier.clientSupplier(),
              activeStreams,
              sender,
              PayloadDecoder.ZERO_COPY);

      Payload response = ByteBufPayload.create("test", "test");

      final AssertSubscriber<Payload> assertSubscriber =
          requestStreamFlux.subscribeWith(new AssertSubscriber<>(0));

      RaceTestUtils.race(requestStreamFlux::cancel, cases.apply(requestStreamFlux));

      if (!sender.isEmpty()) {
        final ByteBuf sentFrame = sender.poll();
        FrameAssert.assertThat(sentFrame)
            .isNotNull()
            .hasNoFragmentsFollow()
            .hasRequestN(expectedN)
            .typeOf(FrameType.REQUEST_STREAM)
            .hasClientSideStreamId()
            .hasPayloadSize(
                "testData".getBytes(CharsetUtil.UTF_8).length
                    + "testMetadata".getBytes(CharsetUtil.UTF_8).length)
            .hasMetadata("testMetadata")
            .hasData("testData")
            .hasStreamId(1);

        Assertions.assertThat(sentFrame.release()).isTrue();
        Assertions.assertThat(sentFrame.refCnt()).isZero();

        final ByteBuf cancelFrame = sender.poll();
        FrameAssert.assertThat(cancelFrame)
            .isNotNull()
            .typeOf(FrameType.CANCEL)
            .hasClientSideStreamId()
            .hasStreamId(1);

        Assertions.assertThat(cancelFrame.release()).isTrue();
        Assertions.assertThat(cancelFrame.refCnt()).isZero();
      }

      Assertions.assertThat(payload.refCnt()).isZero();
      Assertions.assertThat(activeStreams).isEmpty();

      Assertions.assertThat(requestStreamFlux.requested).isEqualTo(STATE_TERMINATED);

      requestStreamFlux.onNext(response);
      Assertions.assertThat(response.refCnt()).isZero();

      requestStreamFlux.onComplete();
      assertSubscriber.assertNotTerminated();

      Assertions.assertThat(activeStreams).isEmpty();
      Assertions.assertThat(sender.isEmpty()).isTrue();
    }
  }

  @ParameterizedTest
  @MethodSource("racingCases")
  public void shouldBeConsistentInCaseOfRacingOfOnCompleteAndRequest(
      Function<RequestStreamFlux, Runnable> cases, long realRequestN, int expectedN) {
    for (int i = 0; i < 1000; i++) {

      final UnboundedProcessor<ByteBuf> sender = new UnboundedProcessor<>();
      final IntObjectMap<Reassemble<?>> activeStreams = new SynchronizedIntObjectHashMap<>();
      final Payload payload = ByteBufPayload.create("testData", "testMetadata");

      final RequestStreamFlux requestStreamFlux =
          new RequestStreamFlux(
              ByteBufAllocator.DEFAULT,
              payload,
              0,
              TestStateAware.empty(),
              StreamIdSupplier.clientSupplier(),
              activeStreams,
              sender,
              PayloadDecoder.ZERO_COPY);

      Payload response = ByteBufPayload.create("test", "test");

      final AssertSubscriber<Payload> assertSubscriber =
          requestStreamFlux.subscribeWith(new AssertSubscriber<>(0));

      assertSubscriber.request(1);

      final ByteBuf sentFrame = sender.poll();
      FrameAssert.assertThat(sentFrame)
          .isNotNull()
          .hasNoFragmentsFollow()
          .hasRequestN(1)
          .typeOf(FrameType.REQUEST_STREAM)
          .hasClientSideStreamId()
          .hasPayloadSize(
              "testData".getBytes(CharsetUtil.UTF_8).length
                  + "testMetadata".getBytes(CharsetUtil.UTF_8).length)
          .hasMetadata("testMetadata")
          .hasData("testData")
          .hasStreamId(1);

      Assertions.assertThat(sentFrame.release()).isTrue();
      Assertions.assertThat(sentFrame.refCnt()).isZero();

      RaceTestUtils.race(requestStreamFlux::onComplete, cases.apply(requestStreamFlux));

      if (!sender.isEmpty()) {
        final ByteBuf requestNFrame = sender.poll();
        FrameAssert.assertThat(requestNFrame)
            .isNotNull()
            .typeOf(FrameType.REQUEST_N)
            .hasRequestN(expectedN)
            .hasClientSideStreamId()
            .hasStreamId(1);

        Assertions.assertThat(requestNFrame.release()).isTrue();
        Assertions.assertThat(requestNFrame.refCnt()).isZero();
      }

      Assertions.assertThat(payload.refCnt()).isZero();
      Assertions.assertThat(activeStreams).isEmpty();

      Assertions.assertThat(requestStreamFlux.requested).isEqualTo(STATE_TERMINATED);

      requestStreamFlux.onNext(response);
      Assertions.assertThat(response.refCnt()).isZero();

      requestStreamFlux.onComplete();
      assertSubscriber.assertTerminated();

      Assertions.assertThat(activeStreams).isEmpty();
      Assertions.assertThat(sender.isEmpty()).isTrue();
    }
  }

  @ParameterizedTest
  @MethodSource("racingCases")
  public void shouldBeConsistentInCaseOfRacingOfOnErrorAndRequest(
      Function<RequestStreamFlux, Runnable> cases, long realRequestN, int expectedN) {
    for (int i = 0; i < 1000; i++) {

      final UnboundedProcessor<ByteBuf> sender = new UnboundedProcessor<>();
      final IntObjectMap<Reassemble<?>> activeStreams = new SynchronizedIntObjectHashMap<>();
      final Payload payload = ByteBufPayload.create("testData", "testMetadata");

      final RequestStreamFlux requestStreamFlux =
          new RequestStreamFlux(
              ByteBufAllocator.DEFAULT,
              payload,
              0,
              TestStateAware.empty(),
              StreamIdSupplier.clientSupplier(),
              activeStreams,
              sender,
              PayloadDecoder.ZERO_COPY);

      Payload response = ByteBufPayload.create("test", "test");

      final AssertSubscriber<Payload> assertSubscriber =
          requestStreamFlux.subscribeWith(new AssertSubscriber<>(0));

      assertSubscriber.request(1);

      final ByteBuf sentFrame = sender.poll();
      FrameAssert.assertThat(sentFrame)
          .isNotNull()
          .hasNoFragmentsFollow()
          .hasRequestN(1)
          .typeOf(FrameType.REQUEST_STREAM)
          .hasClientSideStreamId()
          .hasPayloadSize(
              "testData".getBytes(CharsetUtil.UTF_8).length
                  + "testMetadata".getBytes(CharsetUtil.UTF_8).length)
          .hasMetadata("testMetadata")
          .hasData("testData")
          .hasStreamId(1);

      Assertions.assertThat(sentFrame.release()).isTrue();
      Assertions.assertThat(sentFrame.refCnt()).isZero();

      RuntimeException exception = new RuntimeException("test");
      RaceTestUtils.race(
          () -> requestStreamFlux.onError(exception), cases.apply(requestStreamFlux));

      if (!sender.isEmpty()) {
        final ByteBuf requestNFrame = sender.poll();
        FrameAssert.assertThat(requestNFrame)
            .isNotNull()
            .typeOf(FrameType.REQUEST_N)
            .hasRequestN(expectedN)
            .hasClientSideStreamId()
            .hasStreamId(1);

        Assertions.assertThat(requestNFrame.release()).isTrue();
        Assertions.assertThat(requestNFrame.refCnt()).isZero();
      }

      Assertions.assertThat(payload.refCnt()).isZero();
      Assertions.assertThat(activeStreams).isEmpty();

      Assertions.assertThat(requestStreamFlux.requested).isEqualTo(STATE_TERMINATED);

      requestStreamFlux.onNext(response);
      Assertions.assertThat(response.refCnt()).isZero();

      requestStreamFlux.onComplete();
      assertSubscriber
          .assertTerminated()
          .assertError(RuntimeException.class)
          .assertErrorMessage("test");

      Assertions.assertThat(activeStreams).isEmpty();
      Assertions.assertThat(sender.isEmpty()).isTrue();
    }
  }

  @Test
  public void shouldSentCancelFrameExactlyOnce() {
    for (int i = 0; i < 1000; i++) {

      final UnboundedProcessor<ByteBuf> sender = new UnboundedProcessor<>();
      final IntObjectMap<Reassemble<?>> activeStreams = new SynchronizedIntObjectHashMap<>();
      final Payload payload = ByteBufPayload.create("testData", "testMetadata");

      final RequestStreamFlux requestStreamFlux =
          new RequestStreamFlux(
              ByteBufAllocator.DEFAULT,
              payload,
              0,
              TestStateAware.empty(),
              StreamIdSupplier.clientSupplier(),
              activeStreams,
              sender,
              PayloadDecoder.ZERO_COPY);

      Payload response = ByteBufPayload.create("test", "test");

      final AssertSubscriber<Payload> assertSubscriber =
          requestStreamFlux.subscribeWith(new AssertSubscriber<>(0));

      assertSubscriber.request(1);

      final ByteBuf sentFrame = sender.poll();
      FrameAssert.assertThat(sentFrame)
          .isNotNull()
          .hasNoFragmentsFollow()
          .hasRequestN(1)
          .typeOf(FrameType.REQUEST_STREAM)
          .hasClientSideStreamId()
          .hasPayloadSize(
              "testData".getBytes(CharsetUtil.UTF_8).length
                  + "testMetadata".getBytes(CharsetUtil.UTF_8).length)
          .hasMetadata("testMetadata")
          .hasData("testData")
          .hasStreamId(1);

      Assertions.assertThat(sentFrame.release()).isTrue();
      Assertions.assertThat(sentFrame.refCnt()).isZero();

      RaceTestUtils.race(requestStreamFlux::cancel, requestStreamFlux::cancel);

      final ByteBuf cancelFrame = sender.poll();
      FrameAssert.assertThat(cancelFrame)
          .isNotNull()
          .typeOf(FrameType.CANCEL)
          .hasClientSideStreamId()
          .hasStreamId(1);

      Assertions.assertThat(cancelFrame.release()).isTrue();
      Assertions.assertThat(cancelFrame.refCnt()).isZero();

      Assertions.assertThat(payload.refCnt()).isZero();
      Assertions.assertThat(activeStreams).isEmpty();

      Assertions.assertThat(requestStreamFlux.requested).isEqualTo(STATE_TERMINATED);

      requestStreamFlux.onNext(response);
      Assertions.assertThat(response.refCnt()).isZero();

      requestStreamFlux.onComplete();
      assertSubscriber.assertNotTerminated();

      Assertions.assertThat(activeStreams).isEmpty();
      Assertions.assertThat(sender.isEmpty()).isTrue();
    }
  }

  public static Stream<Arguments> racingCases() {
    return Stream.of(
        Arguments.of(
            (Function<RequestStreamFlux, Runnable>)
                requestStreamFlux -> () -> requestStreamFlux.request(1),
            1L,
            1),
        Arguments.of(
            (Function<RequestStreamFlux, Runnable>)
                requestStreamFlux -> () -> requestStreamFlux.request(Long.MAX_VALUE),
            Long.MAX_VALUE,
            Integer.MAX_VALUE),
        Arguments.of(
            (Function<RequestStreamFlux, Runnable>)
                requestStreamFlux -> () -> requestStreamFlux.request(Long.MAX_VALUE / 2 + 1),
            Long.MAX_VALUE / 2 + 1,
            Integer.MAX_VALUE),
        Arguments.of(
            (Function<RequestStreamFlux, Runnable>)
                requestStreamFlux -> () -> requestStreamFlux.request(Long.MAX_VALUE / 2),
            Long.MAX_VALUE / 2,
            Integer.MAX_VALUE),
        Arguments.of(
            (Function<RequestStreamFlux, Runnable>)
                requestStreamFlux -> () -> requestStreamFlux.request(Long.MAX_VALUE / 4 + 1),
            Long.MAX_VALUE / 4 + 1,
            Integer.MAX_VALUE),
        Arguments.of(
            (Function<RequestStreamFlux, Runnable>)
                requestStreamFlux -> () -> requestStreamFlux.request(Long.MAX_VALUE / 4),
            Long.MAX_VALUE / 4,
            Integer.MAX_VALUE),
        Arguments.of(
            (Function<RequestStreamFlux, Runnable>)
                requestStreamFlux -> () -> requestStreamFlux.request(Integer.MAX_VALUE / 2),
            Integer.MAX_VALUE / 2,
            Integer.MAX_VALUE / 2));
  }

  @Test
  public void checkName() {
    final UnboundedProcessor<ByteBuf> sender = new UnboundedProcessor<>();
    final IntObjectMap<Reassemble<?>> activeStreams = new SynchronizedIntObjectHashMap<>();
    final Payload payload = ByteBufPayload.create("testData", "testMetadata");

    final RequestStreamFlux requestStreamFlux =
        new RequestStreamFlux(
            ByteBufAllocator.DEFAULT,
            payload,
            0,
            TestStateAware.empty(),
            StreamIdSupplier.clientSupplier(),
            activeStreams,
            sender,
            PayloadDecoder.ZERO_COPY);

    Assertions.assertThat(Scannable.from(requestStreamFlux).name())
        .isEqualTo("source(RequestStreamFlux)");
  }

  static final class TestStateAware implements StateAware {

    final Throwable error;

    TestStateAware(Throwable error) {
      this.error = error;
    }

    @Override
    public Throwable error() {
      return error;
    }

    @Override
    public Throwable checkAvailable() {
      return error;
    }

    public static TestStateAware error(Throwable e) {
      return new TestStateAware(e);
    }

    public static TestStateAware empty() {
      return new TestStateAware(null);
    }
  }
}