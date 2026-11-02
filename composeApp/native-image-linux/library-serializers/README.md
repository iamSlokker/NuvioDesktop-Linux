# library-serializers

Supabase's `@Serializable` types that have no generated `$$serializer` class: custom serializers
(`GoTrueErrorResponse`, `Presence`, ...), enums and sealed bases, each with its `Companion`.

Why: Ktor and Supabase look a body's serializer up by class at run time, which reflects on the type
and its `Companion`. `generateNativeMetadata` only registers types with a `$$serializer`, and
`generateLinuxSerializationMetadata` only `com.nuvio` ones, so nothing registered these, and no
trace reaches them: `GoTrueErrorResponse` is only read when the server rejects a sign-in, which a
signed-out smoke never does.

`com.metrolist.innertubex.models.Context` is here for another reason: the probe finds it by name to
learn whether InnerTubeX is compiled in (`-Pnuvio.innertubex=true`), and a QA build reports a lookup
it has no entry for. native-image skips the entry with a warning when the library is absent.

Found by reading the Supabase jars and the v1.29.0 image's inputs, not by a failing run. Probe:
"linux library serializers", which checks these and a sample of the generated ones.
