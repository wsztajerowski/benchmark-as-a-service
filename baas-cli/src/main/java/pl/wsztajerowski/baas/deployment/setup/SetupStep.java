package pl.wsztajerowski.baas.deployment.setup;

/**
 * One step of the walkthrough: check the desired state, then act, or explain what the user has to
 * do. A step reads what earlier steps found from the {@link SetupContext} and never from another
 * step.
 */
interface SetupStep {

    String name();

    Outcome run(SetupContext context) throws Exception;

    /** What a step concluded. */
    sealed interface Outcome {

        /** Nothing to do, or done: go on to the next step. */
        record Next() implements Outcome {}

        /**
         * The user has to act outside {@code baas}. Interactively the explanation is shown and the
         * step runs again on Enter — or {@code resumeAt} does, when the remedy changes what an
         * earlier step found. Unattended, the run stops with exit 1.
         */
        record Wait(String explanation, String resumeAt) implements Outcome {
            Wait(String explanation) {
                this(explanation, null);
            }
        }

        /** End the run with this exit code; the message, when there is one, says why. */
        record Stop(int exitCode, String message) implements Outcome {}

        /** Continue from an earlier step: an answer it gave cannot be used. */
        record GoTo(String step) implements Outcome {}
    }

    static Outcome next() {
        return new Outcome.Next();
    }
}
