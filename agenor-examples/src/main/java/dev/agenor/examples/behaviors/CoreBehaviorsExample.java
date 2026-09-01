package dev.agenor.examples.behaviors;

import dev.agenor.core.BehaviorType;
import dev.agenor.core.annotations.Behavior;
import dev.agenor.runtime.AgenorRuntime;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.behavior.OneShotBehavior;
import dev.agenor.runtime.behavior.composite.FSMBehavior;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The three shapes a behavior can have, in one runnable agent.
 *
 * <p>A behavior is work the runtime drives for you. {@link BehaviorType} answers one question
 * about it — <em>when does this run</em> — and there are exactly three answers:
 *
 * <ol>
 *   <li>{@link BehaviorType#ONE_SHOT} — once, then never again. Startup work.</li>
 *   <li>{@link BehaviorType#CYCLIC} — every interval, until the agent stops. Polling.</li>
 *   <li>{@link BehaviorType#FSM} — a state machine, one step per call. The scheduler
 *       registers it and does <em>not</em> drive it: only the owner knows when the machine
 *       should take its next step, so the owner calls {@code execute()}.</li>
 * </ol>
 *
 * <p>Anything else you might want — retrying, rate limiting, batching, running only under a
 * condition — is not a kind of schedule, so it is not here. Those wrap a call: do them inside
 * the method, or with a library built for them.
 *
 * <p>Two ways to declare a behavior appear below, and they are equivalent. Annotate a method
 * with {@code @Behavior} and the runtime builds it for you; or build a {@code Behavior} object
 * and hand it to {@code addBehavior()}. Use the annotation when a method is the whole
 * behaviour, and the object when it carries state — the FSM here has to be an object, because
 * a state machine is more than one method.
 *
 * <p>Run with:
 * <pre>
 * mvn exec:java -pl agenor-examples \
 *     -Dexec.mainClass="dev.agenor.examples.behaviors.CoreBehaviorsExample"
 * </pre>
 */
public class CoreBehaviorsExample {

    public static void main(String[] args) throws InterruptedException {
        AgenorRuntime runtime = AgenorRuntime.builder().build();

        SensorAgent sensor = new SensorAgent();
        runtime.registerAgent(sensor);
        runtime.start().join();

        // Let the cyclic behavior tick a few times.
        Thread.sleep(2_000);
        runtime.stop().join();

        System.out.println();
        System.out.println("readings taken: " + sensor.readings.get());
        System.out.println("machine ended in state: " + sensor.machine.getCurrentState());
    }

    /** Takes readings, and runs a small state machine over them. */
    static class SensorAgent extends BaseAgent {

        final AtomicInteger readings = new AtomicInteger();

        /**
         * A start-up sequence: wait, summarise once enough readings are in, then be done.
         *
         * <p>Built here rather than annotated: the machine holds states and transitions, which
         * a method signature cannot carry. What makes it a state machine rather than a list of
         * steps is that the transitions are conditions on data — it leaves SAMPLING when the
         * readings say so, not when a timer does.
         *
         * <p>Each step runs the state the machine is <em>currently</em> in, then tests the
         * transitions out of it. The state's work here is a {@link OneShotBehavior}, so it runs
         * on the first step that finds the machine in that state and is quiet afterwards; once
         * the machine reaches DONE there is nothing left for a step to do.
         */
        final FSMBehavior machine = FSMBehavior.builder("startup", "WAITING")
                .state("WAITING", OneShotBehavior.from("waiting",
                        () -> System.out.println("  [fsm] waiting for the first reading")))
                .state("SAMPLING", OneShotBehavior.from("sampling",
                        () -> System.out.println("  [fsm] readings coming in")))
                .state("DONE", OneShotBehavior.from("done",
                        () -> System.out.println("  [fsm] three readings in, start-up complete")))
                .transition("WAITING", "SAMPLING", fsm -> readings.get() >= 1)
                .transition("SAMPLING", "DONE", fsm -> readings.get() >= 3)
                .build();

        SensorAgent() {
            super("sensor", "Sensor");
            addBehavior(machine);
        }

        /** Runs once, when the agent starts. */
        @Behavior(type = BehaviorType.ONE_SHOT)
        public void announce() {
            System.out.println("[one-shot] sensor online");
        }

        /**
         * Runs every 300ms for as long as the agent is up.
         *
         * <p>It also drives the state machine, because an FSM is on-demand: the scheduler will
         * not call {@code execute()} for it, so something has to.
         */
        @Behavior(type = BehaviorType.CYCLIC, interval = "300ms")
        public void sample() {
            int n = readings.incrementAndGet();
            System.out.println("[cyclic] reading " + n);

            machine.execute().join();
        }
    }
}
