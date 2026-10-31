package voucha.sdk;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a transition handler (design 38).
 *
 * <pre>{@code
 * @Transition(nodeType = "Batch", from = "ENRICHED", on = "BatchReadyForSubmission",
 *             to = "SUBMISSION_PENDING")
 * public IPersistentMap submit(NodeContext ctx, Event event) { ... }
 * }</pre>
 *
 * <p>The annotation is read at compile time and folded into the artifact, so the
 * runtime never reflects over handlers to decide what is legal.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Transition {
    String nodeType();
    String from();
    String on();
    String to();

    /** Effects this transition is permitted to create. */
    String[] effects() default {};

    /** Events this transition declares it will wait for. */
    String[] await() default {};

    /** Events this transition emits. */
    String[] emit() default {};

    /** The parent must be in one of these states (a :parent-state rule). */
    String[] parentState() default {};

    /** The parent must not be in any of these states. */
    String[] parentStateForbidden() default {};

    /**
     * An aggregation rule: the named counters on this node must together cover
     * every child (a :counters-cover-children rule).
     */
    String[] countersCoverChildren() default {};

    /**
     * A counter rule: {@code counter} compared with {@code counterValue} using
     * {@code counterOp}, one of "=", "<", "<=", ">", ">=".
     */
    String counter() default "";
    String counterOp() default "=";
    long counterValue() default 0;

    /**
     * An auto transition fires on entering its {@code from} state when its
     * rules hold, with {@code on} as the event the kernel raises. The first auto
     * transition declared from a state whose rules hold is the one taken, so a
     * decision state is a set of auto transitions with exclusive rules and a
     * last one without.
     */
    boolean auto() default false;

    /**
     * The handler is code: it is not read as a plan, and the model checker
     * abstracts it to what the annotation declares. Default: the method is read
     * as a plan and must be written in the extractor's subset.
     */
    boolean code() default false;

    /** Child node types the handler creates, for the model checker (`voucha model`). */
    String[] creates() default {};

    /**
     * Events the handler sends to the children it creates, or to every child when
     * it creates none; for the model checker.
     */
    String[] commands() default {};
}
