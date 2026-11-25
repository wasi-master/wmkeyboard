import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension

// Applies MlKitTelemetryTransform to every variant of a module that packages
// ML Kit: :app, and on Play the :feature:translate and :feature:handwriting
// splits that carry ML Kit's translator and ink recogniser. Scope ALL,
// because the calls it edits sit in the libraries' AARs; isInstrumentable
// keeps it to ML Kit's packages and Play services' GoogleApiManager. The
// rewrite keeps the stack shape, so frames need no recomputing; in :app,
// wmkeyboard.ink-offline-imports already asks for it on edited methods.
// Inert in F-Droid's build, which has no ML Kit to match.
extensions.getByType(AndroidComponentsExtension::class.java).apply {
    onVariants(selector().all()) { variant ->
        variant.instrumentation.transformClassesWith(
            MlKitTelemetryTransform::class.java,
            InstrumentationScope.ALL,
        ) {}
    }
}
